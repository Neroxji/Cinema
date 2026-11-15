package com.cinema.service.purchase;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.config.BookingConfig;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.ShowingStatus;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 锁座服务（spec §4 锁座并发方案六步——本卡实现第 1~5 步；第 6 步 @Scheduled 扫描属 task-04，
 * 但"释放前复查 + 条件更新释放"的释放语句本卡已具备（{@code SeatRepository.conditionalReleaseIfExpiredBefore}
 * + 本服务惰性释放路径），task-04 定时任务直接复用同一 WHERE 条件）。
 *
 * <p>// @Pattern: 无状态并发服务（条件更新+乐观锁，NFR1 有且仅有一个成功）, @UseCase: UC204(选座锁座)/206(惰性释放复用)
 *
 * <p>六步落点：
 * <ol>
 *   <li>事务外预检：读座位快照，非"空闲"且非"锁定已过期"直接返回失败（快速失败，减少数据库压力）</li>
 *   <li>事务边界：{@link TransactionTemplate} 显式圈定——从对 seat 的条件更新到提交，全程一个事务</li>
 *   <li>条件更新（乐观锁）：多座位按 seat.id 升序逐行执行
 *       {@code UPDATE seat SET status='锁定', lock_expire_at=?, lock_user_id=?, version=version+1
 *       WHERE id=? AND status='空闲' AND version=?}；影响行数=0 即抛"座位已被占用"（10005）
 *       整体回滚（UC204-4a）</li>
 *   <li>唯一索引兜底（说明）：order_item 以 (seat_id, valid_flag) 唯一索引防双卖——本卡锁座
 *       <b>不产生</b>订单明细，兜底由 task-04 支付出票生成明细时承载（spec §4.4）</li>
 *   <li>提交：事务提交后锁定原子生效，返回锁定截止时间（前端倒计时仅为展示，不承担释放职责）</li>
 * </ol>
 *
 * <p>UC204-4b 惰性释放：目标座位 status=锁定 且 lock_expire_at 已过期（超时未被 task-04 定时任务
 * 清理）→ 先经"释放前复查 + 条件更新释放"（原子 WHERE：status=锁定 且已过期）释放，再按空闲正常锁定。
 */
@Service
public class SeatLockService {

    /** 锁定结果（spec §4.5：提交后返回锁定截止时间；座位列表按 seat.id 升序）。 */
    public record LockResult(Long showingId, Long userId, List<Long> seatIds, LocalDateTime lockExpireAt) {
    }

    private final SeatRepository seatRepository;
    private final ShowingRepository showingRepository;
    private final BookingConfig bookingConfig;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    public SeatLockService(SeatRepository seatRepository,
                           ShowingRepository showingRepository,
                           BookingConfig bookingConfig,
                           PlatformTransactionManager transactionManager,
                           EntityManager entityManager) {
        this.seatRepository = seatRepository;
        this.showingRepository = showingRepository;
        this.bookingConfig = bookingConfig;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.entityManager = entityManager;
    }

    /**
     * 选座锁座（UC204-4，spec §4 六步之 1~5）。
     *
     * @param showingId 场次 id
     * @param userId    锁定人 user.id
     * @param seatIds   所选座位 id 列表（允许乱序/重复传入，服务内按 seat.id 升序去重后逐行处理）
     * @return 锁定结果（含锁定截止时间 = now + BookingConfig.lockDuration，演示档 30s）
     * @throws BizException NOT_FOUND(10004) 场次不存在 / INVALID_PARAM(10003) 参数或状态不符 /
     *                      SEAT_OCCUPIED(10005) 座位已被占用（并发失败统一提示，UC204-4a）
     */
    public LockResult lockSeats(Long showingId, Long userId, List<Long> seatIds) {
        // ---------- 步骤1：事务外预检（快速失败，减少数据库压力） ----------
        if (showingId == null || userId == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "场次与用户不可为空");
        }
        List<Long> orderedSeatIds = normalizeSeatIds(seatIds);

        Showing showing = showingRepository.findById(showingId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "场次不存在：" + showingId));
        if (showing.getStatus() != ShowingStatus.PUBLISHED) {
            throw new BizException(ErrorCode.INVALID_PARAM, "场次不在可售状态，停止锁座");
        }
        if (!showing.getStartTime().isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.INVALID_PARAM, "场次已开映，停止锁座");
        }

        List<Seat> targets = seatRepository.findByShowingIdAndIdIn(showingId, orderedSeatIds);
        if (targets.size() != orderedSeatIds.size()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "所选座位不存在或不属于该场次");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Seat seat : targets) {
            // 空闲 → 放行；锁定但已过期 → 放行（UC204-4b：进事务先惰性释放再锁定）；其余快速失败
            if (seat.getStatus() == SeatState.SOLD
                    || (seat.getStatus() == SeatState.LOCKED && !isExpired(seat, now))) {
                throw new BizException(ErrorCode.SEAT_OCCUPIED);
            }
        }

        // ---------- 步骤2~4：事务边界 = 对 seat 的条件更新到提交，全程一个事务 ----------
        LocalDateTime lockExpireAt = now.plus(bookingConfig.getLockDuration());
        transactionTemplate.executeWithoutResult(tx ->
                lockRowsInTransaction(showingId, userId, orderedSeatIds, lockExpireAt, now));

        // ---------- 步骤5：提交后锁定原子生效，返回锁定截止时间 ----------
        return new LockResult(showingId, userId, orderedSeatIds, lockExpireAt);
    }

    /**
     * 事务内逐行锁定（步骤3）：seat.id 升序逐行条件更新，任何一行影响行数=0 即抛
     * SEAT_OCCUPIED 经 {@link TransactionTemplate} 整体回滚（多座位要么全锁成功，要么全不生效）。
     */
    private void lockRowsInTransaction(Long showingId, Long userId, List<Long> orderedSeatIds,
                                       LocalDateTime lockExpireAt, LocalDateTime now) {
        for (Long seatId : orderedSeatIds) {
            Seat seat = loadSeat(seatId);
            ensureSeatInShowing(seat, showingId);

            // UC204-4b 惰性释放：锁定且已过期（超时未被定时任务清理）→ 先释放再按空闲锁定
            if (seat.getStatus() == SeatState.LOCKED && isExpired(seat, now)) {
                // "释放前复查 + 条件更新释放"原子化：WHERE id=? AND status='锁定' AND lock_expire_at<now
                int released = seatRepository.conditionalReleaseIfExpiredBefore(
                        seatId, SeatState.LOCKED, SeatState.FREE, now);
                entityManager.flush();
                // 批量 JPQL 更新绕过持久化上下文（一级缓存），清空后重读最新行值
                entityManager.clear();
                seat = loadSeat(seatId);
                if (released == 0 && seat.getStatus() != SeatState.FREE) {
                    // 复查失败：并发窗口内该锁已被他人释放并被重新锁定/售出 → 按占用处理
                    throw new BizException(ErrorCode.SEAT_OCCUPIED);
                }
            }

            if (seat.getStatus() != SeatState.FREE) {
                throw new BizException(ErrorCode.SEAT_OCCUPIED);
            }
            // 步骤3：条件更新（乐观锁）——WHERE id=? AND status='空闲' AND version=?
            int locked = seatRepository.conditionalUpdateOnStatusAndVersion(
                    seatId, SeatState.FREE, seat.getVersion(), SeatState.LOCKED, lockExpireAt, userId);
            if (locked == 0) {
                // 影响行数=0 → 并发失败 → 异常传播出事务 → 整体回滚（NFR1 有且仅有一个成功）
                throw new BizException(ErrorCode.SEAT_OCCUPIED);
            }
            entityManager.flush();
            entityManager.clear();
        }
    }

    /** 事务内按 id 重读座位（清空持久化上下文后此读必落库，取最新行值）。 */
    private Seat loadSeat(Long seatId) {
        return seatRepository.findById(seatId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "座位不存在：" + seatId));
    }

    /** 防御校验：座位必须属于目标场次（预检已保证，事务内对重读值再校验一次）。 */
    private void ensureSeatInShowing(Seat seat, Long showingId) {
        if (!showingId.equals(seat.getShowing().getId())) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "座位" + seat.getId() + "不属于场次" + showingId);
        }
    }

    /** 锁定是否已过期（lock_expire_at 非空且严格早于 now）。 */
    private boolean isExpired(Seat seat, LocalDateTime now) {
        LocalDateTime expireAt = seat.getLockExpireAt();
        return expireAt != null && expireAt.isBefore(now);
    }

    /** 规整座位 id：去空值、去重、按 seat.id 升序（spec §4.3 全系统统一顺序防死锁），并校验单笔数量上限（UC204-3）。 */
    private List<Long> normalizeSeatIds(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "至少选择一个座位");
        }
        List<Long> ids = seatIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_PARAM, "至少选择一个有效座位");
        }
        Integer maxSeats = bookingConfig.getMaxSeatsPerOrder();
        if (maxSeats != null && ids.size() > maxSeats) {
            throw new BizException(ErrorCode.INVALID_PARAM, "单笔最多选 " + maxSeats + " 座（UC204-3）");
        }
        return ids;
    }
}
