package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.event.DomainEventPublisher;
import com.cinema.common.event.RefundCompletedEvent;
import com.cinema.common.event.SeatReleasedEvent;
import com.cinema.config.BookingConfig;
import com.cinema.model.OrderItem;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatState;
import com.cinema.repository.OrderItemRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.TicketOrderRepository;
import com.cinema.service.aftermarket.command.RefundCommand;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退票服务（UC302，命令模式 · Receiver 角色——业务能力由本类承载，RefundCommand 封装调用）。
 *
 * <p>// @Pattern: Command(Receiver角色——退票业务实现,
 * <br>Invoker=RefundService.refund,ConcreteCommand=RefundCommand), @UseCase: UC302(申请退票)+扩展4a/4b/6a/6b
 *
 * <p>退票语义（spec §3.1 行6 / PRD UC302，动作编排顺序）：
 * <ol>
 *   <li>校验（{@link RefundValidator} 纯逻辑：本人/已出票未检票/未过开映前 2 小时）；
 *       4a 已检票或已退票拒绝、4b 过截止拒绝；</li>
 *   <li>订单状态机迁移 ISSUED→REFUNDED（终态）；</li>
 *   <li>order_item.valid_flag 置 NULL——防双卖唯一索引 (seat_id, valid_flag) 释放，座位可重售（spec §4.4）；</li>
 *   <li>座位 SOLD→FREE（状态机 releaseByRefund，@Version 乐观锁兜底并发）；</li>
 *   <li>广播 {@link SeatReleasedEvent}(REFUND) → 候补检查订阅者触发递补（UC305-6a/6b：队列空则无动作）
 *       + 通知订阅者；广播 {@link RefundCompletedEvent} → 模拟退款到账通知。</li>
 * </ol>
 * 事件同事务广播：通知/递补邀请随本事务提交回滚（I8 断言依赖）。
 *
 * <p>undo（{@link #undoRefund}）：补偿性撤销——仅当订单仍为已退票且相关座位全部空闲、
 * 未被他人重新售出时，恢复 ISSUED/明细有效/座位已售；否则拒绝（座位重售后不可逆）。
 * 绕过状态机直接置位属补偿语义（状态机无逆向迁移），仅 CommandHistory.undoLast 可达、不经 HTTP。
 */
// @Pattern: Command(Receiver), @UseCase: UC302/UC303
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final TicketOrderRepository ticketOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatRepository seatRepository;
    private final BookingConfig bookingConfig;
    private final EntityManager entityManager;
    private final DomainEventPublisher eventPublisher;
    private final CommandHistory commandHistory;

    public RefundService(TicketOrderRepository ticketOrderRepository,
                         OrderItemRepository orderItemRepository,
                         SeatRepository seatRepository,
                         BookingConfig bookingConfig,
                         EntityManager entityManager,
                         DomainEventPublisher eventPublisher,
                         CommandHistory commandHistory) {
        this.ticketOrderRepository = ticketOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.seatRepository = seatRepository;
        this.bookingConfig = bookingConfig;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.commandHistory = commandHistory;
    }

    /** 退票结果（controller 组装响应 / 撤销补偿快照）。 */
    public record RefundOutcome(Long orderId, String orderNo, Long showingId, Long userId,
                                List<Long> seatIds, BigDecimal refundAmount,
                                LocalDateTime refundedAt, OrderState status) {
    }

    /**
     * 申请退票（UC302 对外入口）：以 {@link RefundCommand} 执行并记入命令历史（spec §5）。
     *
     * @throws BizException 12004 订单不存在 / 12005 非本人 / 13001 状态不可退 / 13002 已过退票截止
     */
    @Transactional
    public RefundOutcome refund(Long orderId, Long requesterId) {
        RefundCommand command = new RefundCommand(this, orderId, requesterId);
        command.execute(); // 命令模式：Invoker 触发 ConcreteCommand → Receiver.doRefund（结果同时填入命令快照）
        commandHistory.record(command);
        return command.outcome();
    }

    /**
     * 退票业务实现（Receiver 方法，由 RefundCommand.execute 调用；事务由 refund() 圈定）。
     * 校验 → 状态机迁移 → 明细失效 → 座位释放 → 同事务广播释放/退款事件。
     */
    @Transactional
    public RefundOutcome doRefund(Long orderId, Long requesterId) {
        TicketOrder order = ticketOrderRepository.findById(orderId)
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderId));
        Showing showing = order.getShowing();
        // 步骤1：UC302-4 校验（4a/4b 扩展点在纯校验器内，U7 同款逻辑）
        RefundValidator.assertRefundable(order.getStatus(), order.getUser().getId(), requesterId,
                showing.getStartTime(), LocalDateTime.now(), bookingConfig.getRefundDeadline());

        List<OrderItem> validItems = orderItemRepository.findByOrderIdAndValidFlag(orderId, 1L);
        List<Long> seatIds = validItems.stream().map(item -> item.getSeat().getId()).sorted().toList();

        // 步骤2：订单状态机 已出票→已退票（终态，spec §3.1 行6）
        order.refund();
        // 步骤3：明细失效（valid_flag 置 NULL 绕开防双卖唯一索引，座位可重售 spec §4.4）
        for (OrderItem item : validItems) {
            item.setValidFlag(null);
        }
        // 步骤4：座位 已售→空闲（状态机 releaseByRefund；@Version 乐观锁兜底并发窗口）
        for (Long seatId : seatIds) {
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "座位不存在：" + seatId));
            seat.releaseByRefund();
        }
        // 事件携带值先取快照（flush+clear 清空持久化上下文后实体脱管）
        Long userId = order.getUser().getId();
        String orderNo = order.getOrderNo();
        BigDecimal amount = order.getTotalAmount();
        entityManager.flush();
        entityManager.clear();

        // 步骤5：同事务广播——座位释放（→QueueCheckSubscriber 触发递补 + 通知）、退款到账（→通知）
        LocalDateTime now = LocalDateTime.now();
        eventPublisher.publish(new SeatReleasedEvent(orderId, showing.getId(), userId, seatIds,
                SeatReleasedEvent.ReleaseReason.REFUND, now));
        eventPublisher.publish(new RefundCompletedEvent(orderId, orderNo, userId, amount, now));
        log.info("[UC302] 退票成功：orderNo={} 用户 {} 场次 {} 释放 {} 座，模拟退款 ¥{}",
                orderNo, userId, showing.getId(), seatIds.size(), amount);
        return new RefundOutcome(orderId, orderNo, showing.getId(), userId, seatIds, amount, now,
                OrderState.REFUNDED);
    }

    /**
     * 撤销退票（RefundCommand.undo 调用的补偿实现，不经 HTTP 暴露）：
     * 守卫 = 订单仍为已退票、相关座位全部空闲且无他人有效明细；补偿 = 恢复 ISSUED +
     * 明细 valid_flag 复位 1 + 座位条件更新 FREE→SOLD（version+1）。
     */
    @Transactional
    public void undoRefund(RefundOutcome outcome) {
        TicketOrder order = ticketOrderRepository.findById(outcome.orderId())
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + outcome.orderId()));
        if (order.getStatus() != OrderState.REFUNDED) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "仅已退票订单可撤销退票（当前状态：" + order.getStatus() + "）");
        }
        for (Long seatId : outcome.seatIds()) {
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "座位不存在：" + seatId));
            if (seat.getStatus() != SeatState.FREE) {
                throw new BizException(ErrorCode.INVALID_PARAM,
                        "座位 " + seatId + " 已被重新锁定/售出，无法撤销退票");
            }
            if (orderItemRepository.existsBySeatIdAndValidFlag(seatId, 1L)) {
                throw new BizException(ErrorCode.INVALID_PARAM,
                        "座位 " + seatId + " 已被他人重新购买，无法撤销退票");
            }
        }
        // 补偿：状态机无逆向迁移，undo 直接置位（命令补偿语义，字段表记录）
        order.setStatus(OrderState.ISSUED);
        // 明细复位有效（退票时整单失效，此处整单恢复）
        for (OrderItem item : orderItemRepository.findByOrderIdAndValidFlagIsNull(outcome.orderId())) {
            item.setValidFlag(1L);
        }
        // 座位条件更新恢复已售（WHERE status=空闲 原子守卫，乐观锁 version+1）
        for (Long seatId : outcome.seatIds()) {
            int restored = entityManager.createQuery("""
                            UPDATE Seat s SET s.status = :toStatus, s.version = s.version + 1
                             WHERE s.id = :id AND s.status = :fromStatus
                            """)
                    .setParameter("toStatus", SeatState.SOLD)
                    .setParameter("fromStatus", SeatState.FREE)
                    .setParameter("id", seatId)
                    .executeUpdate();
            if (restored == 0) {
                throw new BizException(ErrorCode.INVALID_PARAM,
                        "座位 " + seatId + " 状态已变化，无法撤销退票");
            }
        }
        // 批量 JPQL 绕过持久化上下文：flush 固化 + clear 防后续读到缓存中的旧座位状态（代码库统一口径）
        entityManager.flush();
        entityManager.clear();
        log.info("[UC302-undo] 撤销退票补偿完成：orderId={} 恢复 {} 座为已售", outcome.orderId(), outcome.seatIds().size());
    }
}
