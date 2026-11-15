package com.cinema.task;

import com.cinema.common.event.DomainEventPublisher;
import com.cinema.common.event.SeatReleasedEvent;
import com.cinema.model.Seat;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatState;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 锁定超时释放定时任务（UC206，spec §4.6 第 6 步——task-03 预留的收口）。
 *
 * <p>// @UseCase: UC206(锁定超时释放), @UseCase: 配合 UC204(锁座)/UC205(支付竞态)
 *
 * <p>扫描语义（spec §4.6）：
 * <ol>
 *   <li>短事务扫描 {@code status='锁定' AND lock_expire_at < now} 的座位；</li>
 *   <li>逐座执行"释放前复查 + 条件更新释放"——复查以 UPDATE 的 WHERE 原子化
 *       （WHERE id AND status='锁定' AND lock_expire_at&lt;now，与
 *       {@code SeatRepository.conditionalReleaseIfExpiredBefore} 同一条件，
 *       本卡以 EntityManager JPQL 等价实现，避免改动 task-03 仓库接口）；</li>
 *   <li>释放成功后，按（场次, 用户）取消关联的 PENDING_PAYMENT 订单（状态机 cancel：
 *       待支付→已取消，spec §3.1 行3）——仅当该用户在该场次已无其余锁定时才取消
 *       （防止误取消仍在锁定中的其他选座订单）；</li>
 *   <li>按组广播 {@link SeatReleasedEvent}（ReleaseReason=LOCK_TIMEOUT）→
 *       NoticeSubscriber 落站内通知；候补检查订阅者属 task-05（本卡不建）。</li>
 * </ol>
 *
 * <p>支付竞态安全性（A7/I7）：释放的条件更新与支付出票的条件更新（WHERE status='锁定'
 * AND version=? AND lock_expire_at&gt;now，见 OnlinePayFlow）互斥——同一座位两条 UPDATE
 * 谁先提交谁生效，另一条影响行数=0，天然保证"支付 vs 超时释放有且仅有一个生效"。
 *
 * <p>扫描间隔：{@code cinema.booking.release-scan-interval}（默认 1s，演示档实时性优先，NFR2）。
 */
// @UseCase: UC206
@Component
public class LockExpireReleaseTask {

    private static final Logger log = LoggerFactory.getLogger(LockExpireReleaseTask.class);

    private final EntityManager entityManager;
    private final DomainEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public LockExpireReleaseTask(EntityManager entityManager,
                                 DomainEventPublisher eventPublisher,
                                 PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 定时扫描入口（@Scheduled fixedDelay，间隔 = cinema.booking.release-scan-interval-millis，
     * 默认 1000ms；普通属性占位符，不依赖 Bean 命名）。方法 public 以便测试直接调用
     * （I7 确定性触发 / A7 并发竞态对手方）。
     */
    @Scheduled(fixedDelayString = "${cinema.booking.release-scan-interval-millis:1000}")
    public void releaseExpiredLocks() {
        List<Seat> expired = scanExpired(LocalDateTime.now());
        if (expired.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> releaseBatch(LocalDateTime.now()));
    }

    /**
     * 事务内释放一批过期座位并取消关联订单、广播事件。
     * 包级可见：@Scheduled 触发与测试直调共用；事务上下文由调用方决定
     * （定时触发=独立短事务；测试/I7 直调=加入调用方事务，保证断言确定性）。
     */
    void releaseBatch(LocalDateTime now) {
        List<Seat> expired = scanExpired(now);
        if (expired.isEmpty()) {
            return;
        }

        // 1. 逐座"释放前复查 + 条件更新释放"（WHERE 原子化复查，防与支付/他人释放竞态）
        //    分组键：场次+用户 → 该组本次释放的座位 id（事件按组广播）
        Map<String, ReleasedGroup> groups = new LinkedHashMap<>();
        for (Seat seat : expired) {
            Long showingId = seat.getShowing().getId();
            Long lockUserId = seat.getLockUserId();
            int released = entityManager.createQuery("""
                            UPDATE Seat s
                               SET s.status = :toStatus, s.lockExpireAt = NULL,
                                   s.lockUserId = NULL, s.version = s.version + 1
                             WHERE s.id = :id AND s.status = :expectedStatus
                               AND s.lockExpireAt < :now
                            """)
                    .setParameter("toStatus", SeatState.FREE)
                    .setParameter("expectedStatus", SeatState.LOCKED)
                    .setParameter("id", seat.getId())
                    .setParameter("now", now)
                    .executeUpdate();
            if (released > 0) {
                groups.computeIfAbsent(showingId + "#" + lockUserId,
                                k -> new ReleasedGroup(showingId, lockUserId))
                        .seatIds.add(seat.getId());
            }
        }
        entityManager.flush();
        entityManager.clear();
        if (groups.isEmpty()) {
            return;
        }

        // 2. 按（场次, 用户）取消 PENDING_PAYMENT 订单（状态机：待支付→已取消）
        for (ReleasedGroup group : groups.values()) {
            Long orderId = cancelPendingOrders(group.showingId, group.userId);
            // 3. 广播释放事件（超时原因）→ 通知订阅者落库；候补检查订阅者属 task-05
            eventPublisher.publish(new SeatReleasedEvent(orderId, group.showingId, group.userId,
                    List.copyOf(group.seatIds), SeatReleasedEvent.ReleaseReason.LOCK_TIMEOUT, now));
        }
        log.info("[UC206] 超时释放完成：释放座位 {} 个，覆盖 {} 个（场次,用户）分组",
                groups.values().stream().mapToInt(g -> g.seatIds.size()).sum(), groups.size());
    }

    /** 扫描过期锁定座位（status='锁定' AND lock_expire_at &lt; now），按 seat.id 升序。 */
    private List<Seat> scanExpired(LocalDateTime now) {
        return entityManager.createQuery("""
                        SELECT s FROM Seat s
                         WHERE s.status = :status AND s.lockExpireAt < :now
                         ORDER BY s.id
                        """, Seat.class)
                .setParameter("status", SeatState.LOCKED)
                .setParameter("now", now)
                .getResultList();
    }

    /**
     * 取消（场次, 用户）名下全部待支付订单（spec §3.1 行3：锁定超时→已取消）。
     * 保守复查：该用户在该场次若仍有其余锁定座位（多订单错峰过期的边缘场景），
     * 跳过取消、避免误伤仍在选座中的订单；正常一单一用户场景必为 0。
     *
     * @return 被取消的首个订单 id（供事件关联）；无待支付订单时 null
     */
    private Long cancelPendingOrders(Long showingId, Long userId) {
        if (userId == null) {
            return null;
        }
        long remainingLocks = entityManager.createQuery("""
                        SELECT COUNT(s) FROM Seat s
                         WHERE s.showing.id = :showingId AND s.lockUserId = :userId
                           AND s.status = :locked
                        """, Long.class)
                .setParameter("showingId", showingId)
                .setParameter("userId", userId)
                .setParameter("locked", SeatState.LOCKED)
                .getSingleResult();
        if (remainingLocks > 0) {
            log.warn("[UC206] 用户 {} 在场次 {} 仍有 {} 个锁定座位，跳过取消待支付订单（保守处理）",
                    userId, showingId, remainingLocks);
            return null;
        }
        List<TicketOrder> pendingOrders = entityManager.createQuery("""
                        SELECT o FROM TicketOrder o
                         WHERE o.showing.id = :showingId AND o.user.id = :userId
                           AND o.status = :status
                         ORDER BY o.id
                        """, TicketOrder.class)
                .setParameter("showingId", showingId)
                .setParameter("userId", userId)
                .setParameter("status", OrderState.PENDING_PAYMENT)
                .getResultList();
        Long firstOrderId = null;
        for (TicketOrder order : pendingOrders) {
            order.cancel();
            if (firstOrderId == null) {
                firstOrderId = order.getId();
            }
        }
        return firstOrderId;
    }

    /** 释放分组（场次, 用户）→ 本次释放的座位 id 列表。 */
    private static final class ReleasedGroup {
        final Long showingId;
        final Long userId;
        final List<Long> seatIds = new ArrayList<>();

        ReleasedGroup(Long showingId, Long userId) {
            this.showingId = showingId;
            this.userId = userId;
        }
    }

    /** 供日志/调试使用：当前过期锁定座位数（不产生状态变更）。 */
    public long countExpired(LocalDateTime now) {
        Long count = entityManager.createQuery("""
                        SELECT COUNT(s) FROM Seat s
                         WHERE s.status = :status AND s.lockExpireAt < :now
                        """, Long.class)
                .setParameter("status", SeatState.LOCKED)
                .setParameter("now", now)
                .getSingleResult();
        return count == null ? 0 : count;
    }
}
