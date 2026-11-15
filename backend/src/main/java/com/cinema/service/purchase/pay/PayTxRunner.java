package com.cinema.service.purchase.pay;

import com.cinema.common.event.DomainEventPublisher;
import com.cinema.common.event.RefundCompletedEvent;
import com.cinema.common.event.SeatReleasedEvent;
import com.cinema.model.Seat;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatState;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付事务执行器：圈定支付主事务与作废补记事务两个边界。
 *
 * <p>// @UseCase: UC205(事务边界管理——主流程一事务/作废补记另一事务)
 *
 * <p>为什么拆两个事务（UC205-4a/5a）：
 * <ol>
 *   <li><b>主事务</b>（{@link #executePayFlow}）：①~④ 骨架全程——座位条件更新售出、
 *       订单迁移、明细/取票码落库、出票事件广播（通知随同事务落库）。钩子③抛
 *       {@link SeatLockLostException}（座位已被释放/订单已取消）→ 主事务整体回滚，
 *       杜绝"部分座位已售、订单却作废"的中间态脏数据（A7 终态一致性/I6）。</li>
 *   <li><b>补记事务</b>（{@link #finalizeVoid}）：主事务回滚后，把"支付作废"的事实
 *       落库——订单维持/迁移到已取消态、释放本人残留锁定、网关已扣款则广播模拟退款事件。
 *       这些写操作必须在新的独立事务中提交，不能混入已回滚的主事务。</li>
 * </ol>
 */
// @UseCase: UC205
@Service
public class PayTxRunner {

    private static final Logger log = LoggerFactory.getLogger(PayTxRunner.class);

    private final AbstractPayFlow payFlow;
    private final EntityManager entityManager;
    private final DomainEventPublisher eventPublisher;

    public PayTxRunner(AbstractPayFlow payFlow, EntityManager entityManager,
                       DomainEventPublisher eventPublisher) {
        this.payFlow = payFlow;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 支付主事务：模板骨架 ①~④ 全程一个事务。
     * BizException（校验类失败）随事务回滚向上传播给调用方/全局异常处理；
     * SeatLockLostException 亦回滚，但由 PayFlowService 捕获后转入补记事务。
     */
    @Transactional
    public PayOutcome executePayFlow(PayCommand command) {
        return payFlow.execute(command);
    }

    /**
     * 作废补记事务（UC205-4a）：主事务已整体回滚，此处独立提交"作废事实"。
     * 幂等友好：订单已取消（如超时任务抢先取消）则维持；并发对手支付已成功（已出票）
     * 则不退款不释放、按"不可支付"拒绝（防双付双退）。
     */
    @Transactional
    public PayOutcome finalizeVoid(PayCommand command, boolean gatewaySucceeded) {
        TicketOrder order = entityManager.find(TicketOrder.class, command.orderId());
        if (order == null) {
            return PayOutcome.notPayable(command.orderId(), null, "订单不存在");
        }
        OrderState status = order.getStatus();

        // 并发对手已支付成功（同单重复支付竞态）：不取消、不退款、不释放，按不可支付拒绝
        if (status != OrderState.PENDING_PAYMENT && status != OrderState.CANCELED) {
            log.info("[UC205-4a] 订单 {} 已处于 {}（并发支付已生效），本次支付按不可支付拒绝",
                    order.getOrderNo(), status);
            return PayOutcome.notPayable(order.getId(), order.getOrderNo(), status.name());
        }

        // 订单维持/迁移到已取消态（状态机：待支付→已取消；已取消保持）
        if (status == OrderState.PENDING_PAYMENT) {
            order.cancel();
        }

        // 释放本人在该场次残留的锁定座位（无论是否已过锁定时间：订单终态后不得悬挂锁定）
        List<Seat> held = entityManager.createQuery("""
                        SELECT s FROM Seat s
                         WHERE s.showing.id = :showingId AND s.lockUserId = :userId
                           AND s.status = :locked
                         ORDER BY s.id
                        """, Seat.class)
                .setParameter("showingId", order.getShowing().getId())
                .setParameter("userId", command.userId())
                .setParameter("locked", SeatState.LOCKED)
                .getResultList();
        if (!held.isEmpty()) {
            entityManager.createQuery("""
                            UPDATE Seat s
                               SET s.status = :toStatus, s.lockExpireAt = NULL,
                                   s.lockUserId = NULL, s.version = s.version + 1
                             WHERE s.id IN :ids AND s.status = :expectedStatus
                               AND s.lockUserId = :userId
                            """)
                    .setParameter("toStatus", SeatState.FREE)
                    .setParameter("expectedStatus", SeatState.LOCKED)
                    .setParameter("ids", held.stream().map(Seat::getId).toList())
                    .setParameter("userId", command.userId())
                    .executeUpdate();
            // 批量 JPQL 绕过持久化上下文：flush 固化写入（含上方订单 cancel 的脏检查），
            // clear 防止后续 em.find 读到缓存中的旧座位状态（与 SeatLockService 同口径）
            entityManager.flush();
            entityManager.clear();
            // 广播释放事件（超时原因）→ 通知订阅者落库（同事务）
            eventPublisher.publish(new SeatReleasedEvent(order.getId(), order.getShowing().getId(),
                    command.userId(), held.stream().map(Seat::getId).toList(),
                    SeatReleasedEvent.ReleaseReason.LOCK_TIMEOUT, LocalDateTime.now()));
        }

        // 网关若成功则模拟退款到账（UC205-4a：支付作废并模拟退款）→ RefundCompletedEvent
        if (gatewaySucceeded) {
            eventPublisher.publish(new RefundCompletedEvent(order.getId(), order.getOrderNo(),
                    command.userId(), order.getTotalAmount(), LocalDateTime.now()));
        }
        entityManager.flush();
        log.info("[UC205-4a] 支付作废补记完成：订单 {} 终态={}，释放座位 {} 个，模拟退款={}",
                order.getOrderNo(), order.getStatus(), held.size(), gatewaySucceeded);
        return PayOutcome.voided(order.getId(), order.getOrderNo());
    }
}
