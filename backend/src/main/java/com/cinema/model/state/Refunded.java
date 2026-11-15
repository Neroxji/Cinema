package com.cinema.model.state;

import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;

import java.time.LocalDateTime;

/**
 * 已退票状态（终态，spec §3.1 行6 与行8：退款到账、座位释放后拒绝一切变更）。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC302
 */
public final class Refunded implements OrderStatus {

    /** 无状态单例。 */
    private static final Refunded INSTANCE = new Refunded();

    private Refunded() {
    }

    /** 取本状态单例。 */
    public static Refunded instance() {
        return INSTANCE;
    }

    @Override
    public OrderState stateEnum() {
        return OrderState.REFUNDED;
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

    /** 终态拒绝一切：不可取消。 */
    @Override
    public void cancel(TicketOrder order) {
        throw reject(order);
    }

    /** 终态拒绝一切：已退票不可核销（UC307-3a）。 */
    @Override
    public void checkIn(TicketOrder order, LocalDateTime checkinTime) {
        throw reject(order);
    }

    /** 终态拒绝一切：不可重复退票。 */
    @Override
    public void refund(TicketOrder order) {
        throw reject(order);
    }

    /** 终态拒绝一切：已退票不可改签。 */
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
