package com.cinema.model.state;

import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;

import java.time.LocalDateTime;

/**
 * 待支付状态（订单状态机初始态，spec §3.1 行 1~4）。
 *
 * <p>合法事件：支付成功→已出票；支付失败→自环；锁定超时/主动放弃→已取消。
 * 其余事件（核销/退票/改签）一律拒绝（UC307-3a：待支付/已退票/已检票不可入场）。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC204(下单后)/205(支付)/206(锁定超时)
 */
public final class Pending implements OrderStatus {

    /** 无状态单例（状态对象不携带订单数据，迁移动作经 Context 参数传入）。 */
    private static final Pending INSTANCE = new Pending();

    private Pending() {
    }

    /** 取本状态单例。 */
    public static Pending instance() {
        return INSTANCE;
    }

    @Override
    public OrderState stateEnum() {
        return OrderState.PENDING_PAYMENT;
    }

    /** 行1：待支付--支付成功-->已出票；动作=记录支付时间（座位→已售/终止倒计时/广播出票事件由 task-04 编排）。 */
    @Override
    public void pay(TicketOrder order, LocalDateTime payTime) {
        order.setStatus(OrderState.ISSUED);
        order.setPayTime(payTime);
    }

    /** 行2：待支付--支付失败-->待支付（自环：合法事件，状态不变；座位维持锁定由 task-04 保证）。 */
    @Override
    public void payFailed(TicketOrder order) {
        // 自环：订单保持待支付，无需字段变化
    }

    /** 行3/4：待支付--锁定超时/主动放弃-->已取消（座位→空闲/广播释放事件→候补检查由 task-04/05 编排）。 */
    @Override
    public void cancel(TicketOrder order) {
        order.setStatus(OrderState.CANCELED);
    }

    /** 待支付不可核销（UC307-3a）。 */
    @Override
    public void checkIn(TicketOrder order, LocalDateTime checkinTime) {
        throw reject(order, "待支付订单不可检票核销");
    }

    /** 待支付不可退票（尚无有效票）。 */
    @Override
    public void refund(TicketOrder order) {
        throw reject(order, "待支付订单不可退票");
    }

    /** 待支付不可改签（无票可改）。 */
    @Override
    public void changeShowing(TicketOrder order, Long newShowingId) {
        throw reject(order, "待支付订单不可改签");
    }

    /** 构造"非法迁移拒绝"异常（携带订单与当前状态，便于定位）。 */
    private IllegalStateTransitionException reject(TicketOrder order, String why) {
        return new IllegalStateTransitionException(
                "订单" + String.valueOf(order.getId()) + "当前状态[" + stateEnum() + "]拒绝迁移：" + why);
    }
}
