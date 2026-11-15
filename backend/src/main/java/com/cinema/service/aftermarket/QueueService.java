package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.event.DomainEventPublisher;
import com.cinema.common.event.QueueMatchedEvent;
import com.cinema.config.BookingConfig;
import com.cinema.model.Notification;
import com.cinema.model.QueueRecord;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.enums.QueueStatus;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.ShowingStatus;
import com.cinema.repository.NotificationRepository;
import com.cinema.repository.QueueRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.UserRepository;
import com.cinema.service.purchase.TicketBookingService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 候补队列服务（UC304 加入候补 / UC305 递补确认与超时顺延，task-05）。
 *
 * <p>// @UseCase: UC304(加入候补)/UC305(候补递补——响应时限内确认锁座/超时顺延), @UseCase: I8~I10 被测
 *
 * <p>机制口径（编排者契约决策 3，晨报记录）：响应时限<b>不加列</b>——递补截止 =
 * 递补邀请通知（type=QUEUE_MATCHED，content 内嵌递补凭据令牌"递补凭据Q{queueId}，"）的
 * {@code created_at} + {@link BookingConfig#getQueueResponseLimit()}（默认 3 分钟）。
 * 超时顺延由两条路径驱动，语义一致、幂等可重入：
 * <ul>
 *   <li><b>定时扫描</b>：{@link #sweepExpiredInvites()}（@Scheduled，与 UC206 释放扫描同频，
 *       复用既有配置键 release-scan-interval-millis，不加新配置）；</li>
 *   <li><b>懒惰检查</b>：{@link #claim} 确认前先对本场次执行顺延检查（避免依赖扫描时机）。</li>
 * </ul>
 *
 * <p>递补确认（claim）复用 B 模块编排：{@link TicketBookingService#lockAndCreateOrder}
 * （SeatLockService 条件更新锁座 + 创建 PENDING_PAYMENT 订单），成功后 queue→MATCHED
 * 并广播 {@link QueueMatchedEvent}（通知订阅者落库）。座位不锚定邀请时刻的具体座位——
 * claim 时按"场次当前第一个空闲座位"（seat.id 升序）实时锁定：邀请的座位若被普通购票
 * 抢走而场次尚有其他余座，候选仍可递补成功（最简实现，字段表记录）；全场无余座则 13006。
 *
 * <p>邀请幂等：首位候补已有未过期邀请时不再重复发（防定时扫描反复刷新截止时间）。
 */
// @UseCase: UC304/UC305
@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);

    /** 递补邀请令牌前缀（content 内嵌，供 claim/顺延按 queueId 检索邀请通知）。 */
    public static final String INVITE_TOKEN_PREFIX = "递补凭据Q";

    /** 令牌结尾符（区分 queueId=1 与 queueId=12 等"数字前缀歧义"，随 queueId 一起参与 LIKE 匹配）。 */
    static final String INVITE_TOKEN_SUFFIX = "，";

    /** 邀请通知里的截止时间展示格式。 */
    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QueueRepository queueRepository;
    private final SeatRepository seatRepository;
    private final ShowingRepository showingRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final TicketBookingService ticketBookingService;
    private final BookingConfig bookingConfig;
    private final DomainEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public QueueService(QueueRepository queueRepository,
                        SeatRepository seatRepository,
                        ShowingRepository showingRepository,
                        UserRepository userRepository,
                        NotificationRepository notificationRepository,
                        TicketBookingService ticketBookingService,
                        BookingConfig bookingConfig,
                        DomainEventPublisher eventPublisher,
                        EntityManager entityManager) {
        this.queueRepository = queueRepository;
        this.seatRepository = seatRepository;
        this.showingRepository = showingRepository;
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.ticketBookingService = ticketBookingService;
        this.bookingConfig = bookingConfig;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    /** 加入候补结果。 */
    public record QueueJoinResult(Long queueId, Long showingId, Long userId, Integer rank, QueueStatus status) {
    }

    /** 递补确认结果（含复用 B 模块锁座下单得到的待支付订单信息）。 */
    public record QueueClaimResult(Long queueId, QueueStatus status, Long orderId, String orderNo,
                                   Long showingId, Long seatId, LocalDateTime lockExpireAt,
                                   BigDecimal estimatedAmount) {
    }

    // =====================================================================
    // UC304 · 加入候补（满座场次才可候补，重复候补拒绝）
    // =====================================================================

    /**
     * 加入候补（UC304）。
     *
     * @throws BizException 11005 场次不存在 / 13014 场次不满足候补条件（须未开映的满座场次）/
     *                      13003 重复候补（同场次已有排队中的记录）
     */
    @Transactional
    public QueueJoinResult join(Long showingId, Long userId) {
        Showing showing = showingRepository.findById(showingId)
                .orElseThrow(() -> new BizException(ErrorCode.SHOWING_NOT_FOUND, "场次不存在：" + showingId));
        LocalDateTime now = LocalDateTime.now();

        // UC304 前置：仅"未开映的满座场次"可候补（status=PUBLISHED 且未开映且全部座位已售）
        boolean full = showing.getStatus() == ShowingStatus.PUBLISHED
                && showing.getStartTime().isAfter(now)
                && isFullySold(showingId);
        if (!full) {
            throw new BizException(ErrorCode.QUEUE_JOIN_REJECTED);
        }
        if (queueRepository.existsByShowingIdAndUserIdAndStatus(showingId, userId, QueueStatus.WAITING)) {
            throw new BizException(ErrorCode.QUEUE_ALREADY_JOINED);
        }

        // rank 分配 = 该场次历史最大 rank + 1（过期/取消记录占号不重排，保证先来后到语义稳定）
        Integer maxRank = queueRepository.findMaxRankByShowingId(showingId);
        int rank = (maxRank == null ? 0 : maxRank) + 1;

        QueueRecord record = new QueueRecord();
        record.setShowing(showing);
        record.setUser(userRepository.getReferenceById(userId));
        record.setRank(rank);
        record.setStatus(QueueStatus.WAITING);
        record = queueRepository.save(record);
        log.info("[UC304] 加入候补：用户 {} 场次 {} queueId={} rank={}", userId, showingId, record.getId(), rank);
        return new QueueJoinResult(record.getId(), showingId, userId, rank, record.getStatus());
    }

    // =====================================================================
    // UC305 · 候补确认递补并锁座（时限内确认 → 锁座 + 待支付订单 + queue→MATCHED）
    // =====================================================================

    /**
     * 候补确认递补（UC305）：确认前先做本场次懒惰顺延检查（超时者过期并邀请下一位），
     * 通过后为候选人直接锁座并创建 PENDING_PAYMENT 订单（复用 B 模块锁座下单编排）。
     *
     * @throws BizException 13004 候补记录不存在或状态不符 / 12005 非本人候补记录 /
     *                      13005 递补确认超时 / 13006 递补座位已被其他观众选购
     */
    @Transactional
    public QueueClaimResult claim(Long queueId, Long requesterId) {
        QueueRecord record = queueRepository.findById(queueId)
                .orElseThrow(() -> new BizException(ErrorCode.QUEUE_RECORD_INVALID, "候补记录不存在：" + queueId));
        if (!record.getUser().getId().equals(requesterId)) {
            throw new BizException(ErrorCode.ORDER_FORBIDDEN, "无权操作他人候补记录");
        }
        Long showingId = record.getShowing().getId();

        // 懒惰顺延：先把本场次已过响应时限的递补资格过期（可能正是本记录）
        advanceExpiredInvites(showingId);
        QueueRecord fresh = queueRepository.findById(queueId)
                .orElseThrow(() -> new BizException(ErrorCode.QUEUE_RECORD_INVALID, "候补记录不存在：" + queueId));
        if (fresh.getStatus() == QueueStatus.EXPIRED) {
            throw new BizException(ErrorCode.QUEUE_CLAIM_EXPIRED);
        }
        if (fresh.getStatus() != QueueStatus.WAITING) {
            throw new BizException(ErrorCode.QUEUE_RECORD_INVALID,
                    "候补记录状态不符（" + fresh.getStatus() + "），无法确认递补");
        }
        Notification invite = latestInvite(queueId);
        if (invite == null) {
            throw new BizException(ErrorCode.QUEUE_RECORD_INVALID, "尚未收到递补通知，请等待座位释放");
        }
        LocalDateTime deadline = invite.getCreatedAt().plus(bookingConfig.getQueueResponseLimit());
        if (!LocalDateTime.now().isBefore(deadline)) {
            // 并发窗口兜底（扫描未及）：邀请已超时仍按超时拒绝（顺延由扫描/下次确认触发）
            throw new BizException(ErrorCode.QUEUE_CLAIM_EXPIRED);
        }

        // 递补锁座：取场次当前第一个空闲座位（seat.id 升序），复用 B 模块锁座下单编排
        Seat freeSeat = seatRepository.findFirstByShowingIdAndStatusOrderByIdAsc(showingId, SeatState.FREE)
                .orElseThrow(() -> new BizException(ErrorCode.QUEUE_SEAT_TAKEN));
        TicketBookingService.LockOrderResult lock;
        try {
            lock = ticketBookingService.lockAndCreateOrder(showingId, requesterId, List.of(freeSeat.getId()));
        } catch (BizException e) {
            if (e.getCode() == ErrorCode.SEAT_OCCUPIED.getCode()) {
                // 预检与条件更新之间的并发窗口：座位被普通购票抢走 → 候选继续排队（记录保持 WAITING）
                throw new BizException(ErrorCode.QUEUE_SEAT_TAKEN);
            }
            throw e;
        }

        // lockAndCreateOrder 内部 flush+clear 清空过持久化上下文 → 重读后置 MATCHED（防脱管丢失更新）
        QueueRecord claimed = queueRepository.findById(queueId)
                .orElseThrow(() -> new BizException(ErrorCode.QUEUE_RECORD_INVALID, "候补记录不存在：" + queueId));
        claimed.setStatus(QueueStatus.MATCHED);
        entityManager.flush();

        // 递补成功事件（spec §7.7：递补成功 → 通知订阅者）同事务广播
        LocalDateTime now = LocalDateTime.now();
        eventPublisher.publish(new QueueMatchedEvent(queueId, lock.orderId(), showingId,
                freeSeat.getId(), requesterId, now));
        log.info("[UC305] 递补确认成功：queueId={} 用户 {} 场次 {} 锁定座位 {}，待支付订单 {}（截止 {}）",
                queueId, requesterId, showingId, freeSeat.getId(), lock.orderNo(), deadline);
        return new QueueClaimResult(queueId, claimed.getStatus(), lock.orderId(), lock.orderNo(),
                showingId, freeSeat.getId(), lock.lockExpireAt(), lock.estimatedAmount());
    }

    // =====================================================================
    // 观察者入口（QueueCheckSubscriber 回调）+ 超时顺延（定时扫描 + 懒惰检查共用）
    // =====================================================================

    /**
     * 座位释放后的候补检查入口（spec §7.7：座位释放 → 候补检查订阅者触发递补 UC305）。
     * 队列空则无动作（UC302-6b / I10）；有排队者则邀请第一位（幂等）。
     * 由 QueueCheckSubscriber 在发布方事务内回调（邀请通知随释放事务提交/回滚）。
     */
    @Transactional
    public void onSeatReleased(Long showingId) {
        inviteNextWaiting(showingId);
    }

    /**
     * 邀请当前第一位候补（幂等）：队列空 → 无动作；首位已有未过期邀请 → 不重发；
     * 场次无空闲座位 → 不发（无可递补之物）。邀请通知 content 内嵌递补凭据令牌与截止时间。
     */
    @Transactional
    public void inviteNextWaiting(Long showingId) {
        List<QueueRecord> waiting = queueRepository
                .findByShowingIdAndStatusOrderByRankAscIdAsc(showingId, QueueStatus.WAITING);
        if (waiting.isEmpty()) {
            return; // I10：队列为空，座位直接可售，无递补通知
        }
        QueueRecord first = waiting.get(0); // 按 rank 升序取第一位（UC305 按序递补）
        Notification invite = latestInvite(first.getId());
        if (invite != null && LocalDateTime.now().isBefore(
                invite.getCreatedAt().plus(bookingConfig.getQueueResponseLimit()))) {
            return; // 已有未过期邀请，幂等跳过（防扫描反复刷新截止）
        }
        Seat freeSeat = seatRepository
                .findFirstByShowingIdAndStatusOrderByIdAsc(showingId, SeatState.FREE).orElse(null);
        if (freeSeat == null) {
            return; // 无空闲座位可递补（座位可能已被普通购票售出），不产生邀请
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plus(bookingConfig.getQueueResponseLimit());
        String content = "您候补的场次 " + showingId + "（" + first.getShowing().getMovie().getTitle()
                + "）出现余座（递补凭据Q" + first.getId() + INVITE_TOKEN_SUFFIX + "座位 "
                + freeSeat.getRowNum() + "排" + freeSeat.getColNum() + "座），请在 "
                + DEADLINE_FORMAT.format(deadline) + " 前确认递补锁座，逾期自动顺延下一位候补。";
        Notification notice = new Notification();
        notice.setUser(first.getUser());
        notice.setType("QUEUE_MATCHED");
        notice.setTitle("候补递补通知");
        notice.setContent(content);
        notice.setReadFlag(false);
        notice.setCreatedAt(now);
        notificationRepository.save(notice);
        log.info("[UC305] 递补邀请：queueId={} 用户 {} 场次 {} 空闲座位 {}，确认截止 {}",
                first.getId(), first.getUser().getId(), showingId, freeSeat.getId(), deadline);
    }

    /**
     * 递补超时顺延（UC305"超时未确认→EXPIRED 顺延下一位"）：把本场次首位候补中
     * "邀请已过响应时限仍未确认"的记录逐个置 EXPIRED；幂等可重入（无邀请或未超时即停）。
     * 被 claim 的懒惰检查与定时扫描共用。
     */
    @Transactional
    public void advanceExpiredInvites(Long showingId) {
        while (true) {
            List<QueueRecord> waiting = queueRepository
                    .findByShowingIdAndStatusOrderByRankAscIdAsc(showingId, QueueStatus.WAITING);
            if (waiting.isEmpty()) {
                return;
            }
            QueueRecord first = waiting.get(0);
            Notification invite = latestInvite(first.getId());
            if (invite == null) {
                return; // 首位尚未被邀请，无资格可过期
            }
            if (LocalDateTime.now().isBefore(invite.getCreatedAt().plus(bookingConfig.getQueueResponseLimit()))) {
                return; // 首位邀请仍在响应时限内
            }
            first.setStatus(QueueStatus.EXPIRED);
            queueRepository.saveAndFlush(first);
            log.info("[UC305] 递补超时顺延：queueId={} 用户 {} 场次 {} 已过期，顺延下一位",
                    first.getId(), first.getUser().getId(), showingId);
            // 循环继续：若下一位也持有过期邀请同样过期；无邀请即停
        }
    }

    /**
     * 递补超时定时扫描（@Scheduled）：与 UC206 锁定超时释放同频（复用既有配置键
     * cinema.booking.release-scan-interval-millis，不加新配置——编排者契约决策 3）。
     * 每轮：对本场次执行"过期顺延 + 邀请下一位"（含座位回空后补邀的自愈）。
     * 方法 public 以便集成测试确定性直调（I9，与 I7 直调释放任务同法）。
     */
    @Scheduled(fixedDelayString = "${cinema.booking.release-scan-interval-millis:1000}")
    @Transactional
    public void sweepExpiredInvites() {
        List<QueueRecord> waiting = queueRepository.findByStatusOrderByShowingIdAscRankAsc(QueueStatus.WAITING);
        if (waiting.isEmpty()) {
            return;
        }
        Set<Long> showingIds = new HashSet<>();
        for (QueueRecord record : waiting) {
            showingIds.add(record.getShowing().getId());
        }
        for (Long showingId : showingIds) {
            advanceExpiredInvites(showingId);
            inviteNextWaiting(showingId);
        }
    }

    // ---------------- 内部方法 ----------------

    /** 场次是否满座（全部座位已售——UC304 候补准入口径）。 */
    private boolean isFullySold(Long showingId) {
        List<Seat> seats = seatRepository.findByShowingId(showingId);
        return !seats.isEmpty() && seats.stream().allMatch(seat -> seat.getStatus() == SeatState.SOLD);
    }

    /** 候补记录最新一条递补邀请通知（type=QUEUE_MATCHED 且 content 含递补凭据令牌），无则 null。 */
    private Notification latestInvite(Long queueId) {
        List<Notification> hits = notificationRepository.findByTypeAndContentContainingOrderByIdDesc(
                "QUEUE_MATCHED", INVITE_TOKEN_PREFIX + queueId + INVITE_TOKEN_SUFFIX, PageRequest.of(0, 1));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 供测试断言：某候补记录的递补确认截止时间（邀请 created_at + 响应时限；决策 3 口径）。 */
    public java.util.Optional<LocalDateTime> claimDeadlineOf(Long queueId) {
        Notification invite = latestInvite(queueId);
        return invite == null ? java.util.Optional.empty()
                : java.util.Optional.of(invite.getCreatedAt().plus(bookingConfig.getQueueResponseLimit()));
    }
}
