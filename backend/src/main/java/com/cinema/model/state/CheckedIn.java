package com.cinema.model.state;

import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;

import java.time.LocalDateTime;

/**
 * 已检票状态（终态，spec §3.1 行5 与行8：核销入场后拒绝一切变更）。
 *
 * <p>UC307-5a"重复核销并发提交幂等处理"由核销服务基于本状态守卫实现：
 * 服务层捕获本状态抛出的拒绝后判等返回"已核销"，状态机本身坚持终态语义不放松。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC307
 */
public final class CheckedIn implements OrderStatus {

    /** 无状态单例。 */
    private static final CheckedIn INSTANCE = new CheckedIn();

    private CheckedIn() {
    }

    /** 取本状态单例。 */
    public static CheckedIn instance() {
        return INSTANCE;
    }

    @Override
    public OrderState stateEnum() {
        return OrderState.CHECKED_IN;
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

    /** 终态拒绝一切：已检票不可重复核销（幂等由服务层基于此守卫，spec §3.1 行8）。 */
    @Override
    public void checkIn(TicketOrder order, LocalDateTime checkinTime) {
        throw reject(order);
    }

    /** 终态拒绝一切：已检票不可退票（测试计划 U2 明确例，UC302-4a）。 */
    @Override
    public void refund(TicketOrder order) {
        throw reject(order);
    }

    /** 终态拒绝一切：已检票不可改签。 */
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
