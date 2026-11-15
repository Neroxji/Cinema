package com.cinema.model.state;

import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;

import java.time.LocalDateTime;

/**
 * 已取消状态（终态，spec §3.1 行3/4 与行8：锁定超时或观众主动放弃后拒绝一切变更）。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC204(放弃)/206(锁定超时)
 */
public final class Canceled implements OrderStatus {

    /** 无状态单例。 */
    private static final Canceled INSTANCE = new Canceled();

    private Canceled() {
    }

    /** 取本状态单例。 */
    public static Canceled instance() {
        return INSTANCE;
    }

    @Override
    public OrderState stateEnum() {
        return OrderState.CANCELED;
    }

    /** 终态拒绝一切：不可再支付。 */
    @Override
    public void pay(TicketOrder order, LocalDateTime payTime) {
        throw reject(order);
    }

    /** 终态拒绝一切：支付失败事件不适用于终态。 */
    @Override
    public void payFailed(TicketOrder order) {
        throw reject(order);
    }

    /** 终态拒绝一切：不可重复取消。 */
    @Override
    public void cancel(TicketOrder order) {
        throw reject(order);
    }

    /** 终态拒绝一切：已取消订单不可核销（UC307-3a）。 */
    @Override
    public void checkIn(TicketOrder order, LocalDateTime checkinTime) {
        throw reject(order);
    }

    /** 终态拒绝一切：已取消订单不可退票。 */
    @Override
    public void refund(TicketOrder order) {
        throw reject(order);
    }

    /** 终态拒绝一切：已取消订单不可改签。 */
    @Override
    public void changeShowing(TicketOrder order, Long newShowingId) {
        throw reject(order);
    }

    /** 构造"非法迁移拒绝"异常（终态拒绝一切变更）。 */
    private IllegalStateTransitionException reject(TicketOrder order) {
        return new IllegalStateTransitionException(
                "订单" + String.valueOf(order.getId()) + "当前状态[" + stateEnum()
                        + "]为终态，拒绝一切变更（spec §3.1 行8）");
    }
}
