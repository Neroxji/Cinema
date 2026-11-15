package com.cinema.service.purchase.pay;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.event.DomainEventPublisher;
import com.cinema.common.event.TicketIssuedEvent;
import com.cinema.model.OrderItem;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatState;
import com.cinema.service.admin.strategy.PriceStrategy;
import com.cinema.service.admin.strategy.PriceStrategyResolver;
import com.cinema.service.purchase.PickupCodeGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线购票支付流程（模板方法具体子类，UC205 全语义）。
 *
 * <p>// @Pattern: Template Method(ConcreteClass——实现四钩子,
 * <br>骨架见 AbstractPayFlow), @UseCase: UC205(支付出票)+扩展3a/4a/5a
 *
 * <p>UC205 语义落点：
 * <ul>
 *   <li>基本路径 4/5：复查座位仍处"本人锁定且未超时"→ 条件更新逐座售出
 *       （WHERE status='锁定' AND version=? AND lock_user_id=本人 AND lock_expire_at&gt;now，
 *       影响行数=0 即座位丢失）→ 订单状态机 pay（待支付→已出票+支付时间）→
 *       order_item 落库（price_snapshot 复用 {@link PriceStrategyResolver} 按座位 zone 计价，
 *       total_amount=明细合计）→ 取票码生成（8 位全库唯一）。</li>
 *   <li>3a 网关失败：订单状态机 payFailed 自环（保持待支付），座位维持锁定至超时，
 *       返回 GATEWAY_FAILED（错误码 12001 由 controller 映射）。</li>
 *   <li>4a 支付时座位已被超时释放（订单已取消，或条件更新复查失败）：抛
 *       {@link SeatLockLostException} 整体回滚，由 PayTxRunner.finalizeVoid 补记——
 *       订单维持已取消态、释放残留锁定、网关若成功则模拟退款并广播
 *       RefundCompletedEvent，提示重新选座（12002）。</li>
 *   <li>5a 状态更新异常：③ 钩子任何异常传播出事务边界 → 整体回滚无脏数据（I6）。</li>
 * </ul>
 *
 * <p>并发安全性：座位售出的条件更新与超时释放任务（LockExpireReleaseTask）的条件释放
 * 互斥于同一行——两条 UPDATE 谁先提交谁生效（A7：支付 vs 超时释放竞态有且仅有一个生效）。
 */
// @Pattern: Template Method(ConcreteClass), @UseCase: UC205
@Component
public class OnlinePayFlow extends AbstractPayFlow {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final PriceStrategyResolver priceStrategyResolver;
    private final PickupCodeGenerator pickupCodeGenerator;
    private final MockPayGateway payGateway;
    private final DomainEventPublisher eventPublisher;

    public OnlinePayFlow(EntityManager entityManager,
                         ObjectMapper objectMapper,
                         PriceStrategyResolver priceStrategyResolver,
                         PickupCodeGenerator pickupCodeGenerator,
                         MockPayGateway payGateway,
                         DomainEventPublisher eventPublisher) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
        this.priceStrategyResolver = priceStrategyResolver;
        this.pickupCodeGenerator = pickupCodeGenerator;
        this.payGateway = payGateway;
        this.eventPublisher = eventPublisher;
    }

    // ---------------- 钩子①：参数与状态校验 ----------------

    @Override
    protected PayContext validate(PayCommand command) {
        if (command.orderId() == null || command.userId() == null) {
            throw new BizException(ErrorCode.INVALID_PARAM, "订单与用户不可为空");
        }
        TicketOrder order = entityManager.find(TicketOrder.class, command.orderId());
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + command.orderId());
        }
        if (!command.userId().equals(order.getUser().getId())) {
            throw new BizException(ErrorCode.ORDER_FORBIDDEN, "无权支付他人订单");
        }
        // UC205 前置：本人待支付订单方可支付；已取消（4a：座位已被超时释放）放行进入作废流程
        OrderState status = order.getStatus();
        if (status != OrderState.PENDING_PAYMENT && status != OrderState.CANCELED) {
            throw new BizException(ErrorCode.ORDER_NOT_PAYABLE,
                    "订单当前状态不可支付：" + status);
        }
        // 本人在该场次仍持有的锁定座位快照（权威判定在钩子③的条件更新处；此处仅采样）
        List<Seat> heldSeats = entityManager.createQuery("""
                        SELECT s FROM Seat s
                         WHERE s.showing.id = :showingId AND s.lockUserId = :userId
                           AND s.status = :locked
                         ORDER BY s.id
                        """, Seat.class)
                .setParameter("showingId", order.getShowing().getId())
                .setParameter("userId", command.userId())
                .setParameter("locked", SeatState.LOCKED)
                .getResultList();
        return new PayContext(command, order, heldSeats, order.getShowing().getId());
    }

    // ---------------- 钩子②：调用模拟网关 ----------------

    @Override
    protected GatewayResult callGateway(PayContext context) {
        TicketOrder order = context.order();
        return payGateway.charge(order.getOrderNo(), order.getTotalAmount());
    }

    // ---------------- 钩子③：状态迁移与明细落库 ----------------

    @Override
    protected PayOutcome applyStateChange(PayContext context, GatewayResult gateway) {
        TicketOrder order = context.order();

        // UC205-4a：订单已不在待支付态（座位已被超时释放/取消）→ 支付作废，整体回滚后补记
        if (order.getStatus() != OrderState.PENDING_PAYMENT) {
            throw new SeatLockLostException(gateway.success(),
                    "支付时订单已取消（座位已被超时释放），支付作废");
        }

        // UC205-3a：网关失败 → 订单保持待支付（状态机自环），座位维持锁定至倒计时结束
        if (!gateway.success()) {
            order.payFailed();
            return PayOutcome.gatewayFailed(order.getId(), order.getOrderNo());
        }

        // UC205-4：复查座位仍处本人锁定且未超时 → 条件更新逐座售出（原子，乐观锁+未过期约束）
        if (context.heldSeats().isEmpty()) {
            throw new SeatLockLostException(true, "座位已被释放，支付作废");
        }
        LocalDateTime now = LocalDateTime.now();
        for (Seat seat : context.heldSeats()) {
            int sold = entityManager.createQuery("""
                            UPDATE Seat s
                               SET s.status = :toStatus, s.lockExpireAt = NULL,
                                   s.lockUserId = NULL, s.version = s.version + 1
                             WHERE s.id = :id AND s.status = :expectedStatus
                               AND s.version = :expectedVersion
                               AND s.lockUserId = :userId AND s.lockExpireAt > :now
                            """)
                    .setParameter("toStatus", SeatState.SOLD)
                    .setParameter("expectedStatus", SeatState.LOCKED)
                    .setParameter("id", seat.getId())
                    .setParameter("expectedVersion", seat.getVersion())
                    .setParameter("userId", context.command().userId())
                    .setParameter("now", now)
                    .executeUpdate();
            if (sold == 0) {
                // 支付与释放并发（A7）：座位在复查后被释放/售出/过期 → 作废，整体回滚（UC205-4a）
                throw new SeatLockLostException(true,
                        "座位 " + seat.getId() + " 在支付期间被释放，支付作废");
            }
        }
        // 批量 JPQL 绕过持久化上下文（与 SeatLockService 同口径），清空后重读最新值
        entityManager.flush();
        entityManager.clear();

        // 订单状态机迁移：待支付→已出票（记录支付时间，spec §3.1 行1）
        TicketOrder fresh = reloadOrder(order.getId());
        fresh.pay(now);

        // 取票码（8 位数字、全库唯一、冲突重试，U8）
        fresh.setPickupCode(pickupCodeGenerator.generateUnique());

        // order_item 落库：price_snapshot 复用 PriceStrategyResolver 按座位 zone 计价（UC105 快照口径）
        Showing showing = entityManager.find(Showing.class, context.showingId());
        JsonNode params = parseParams(showing.getPriceParams());
        PriceStrategy strategy = priceStrategyResolver.of(showing.getPriceType());
        List<Long> seatIds = context.heldSeats().stream().map(Seat::getId).toList();
        BigDecimal total = BigDecimal.ZERO;
        for (Long seatId : seatIds) {
            Seat seat = entityManager.find(Seat.class, seatId);
            OrderItem item = new OrderItem();
            item.setOrder(fresh);
            item.setSeat(seat);
            BigDecimal snapshot = strategy.calculate(seat.getZone(), params);
            item.setPriceSnapshot(snapshot);
            item.setValidFlag(1L);
            entityManager.persist(item);
            total = total.add(snapshot);
        }
        // total_amount = 明细快照合计（以出票时计价为准）
        fresh.setTotalAmount(total);
        entityManager.flush();

        return PayOutcome.success(fresh.getId(), fresh.getOrderNo(), fresh.getPickupCode(),
                total, fresh.getPayTime(), seatIds);
    }

    // ---------------- 钩子④：事件广播 ----------------

    @Override
    protected void publishEvents(PayContext context, PayOutcome outcome) {
        if (outcome.type() == PayOutcome.PayOutcomeType.SUCCESS) {
            // spec §7.7 事件清单：出票成功 → 通知订阅者写站内通知（UC306）
            eventPublisher.publish(new TicketIssuedEvent(outcome.orderId(), outcome.orderNo(),
                    context.showingId(), context.command().userId(), outcome.seatIds(),
                    outcome.paidAmount(), outcome.payTime()));
        }
        // GATEWAY_FAILED 无对应事件（事件清单 4 类均不适用）；VOIDED 的退款/释放事件
        // 由 PayTxRunner.finalizeVoid 在独立补记事务中广播（③ 已回滚，④ 不可达）。
    }

    /** 批量更新 clear 后按 id 重读订单（取最新行值，状态机迁移基于当前库态）。 */
    private TicketOrder reloadOrder(Long orderId) {
        TicketOrder fresh = entityManager.find(TicketOrder.class, orderId);
        if (fresh == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderId);
        }
        return fresh;
    }

    private JsonNode parseParams(String priceParams) {
        try {
            return objectMapper.readTree(priceParams == null ? "{}" : priceParams);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "场次计价参数解析失败：" + priceParams);
        }
    }
}
