package com.cinema.model.state;

import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;

import java.time.LocalDateTime;

/**
 * 已出票状态（spec §3.1 行 5~7：可检票、可退票、可改签自迁移）。
 *
 * <p>合法事件：检票核销→已检票；退票→已退票；改签→已出票（新场次自迁移）。
 * 其余事件（再次支付/支付失败/取消）一律拒绝——重复支付防双付，超时取消仅适用于待支付。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC307(检票)/302(退票)/303(改签)
 */
public final class Issued implements OrderStatus {

    /** 无状态单例。 */
    private static final Issued INSTANCE = new Issued();

    private Issued() {
    }

    /** 取本状态单例。 */
    public static Issued instance() {
        return INSTANCE;
    }

    @Override
    public OrderState stateEnum() {
        return OrderState.ISSUED;
    }

    /** 已出票再支付=重复支付，拒绝（防双付；幂等重试由支付服务按 order_no 判重处理）。 */
    @Override
    public void pay(TicketOrder order, LocalDateTime payTime) {
        throw reject(order, "订单已出票，不可重复支付");
    }

    /** 支付失败事件仅待支付态合法，已出票收到即非法。 */
    @Override
    public void payFailed(TicketOrder order) {
        throw reject(order, "支付失败事件仅适用于待支付订单");
    }

    /** 已出票订单不存在锁定超时概念（超时取消仅适用于待支付），拒绝。 */
    @Override
    public void cancel(TicketOrder order) {
        throw reject(order, "已出票订单不可按锁定超时/放弃取消");
    }

    /** 行5：已出票--检票核销-->已检票（终态）；动作=记录核销时间（操作员记录由核销服务承载，task-05）。 */
    @Override
    public void checkIn(TicketOrder order, LocalDateTime checkinTime) {
        order.setStatus(OrderState.CHECKED_IN);
        order.setCheckinTime(checkinTime);
    }

    /** 行6：已出票--退票-->已退票（终态）；座位→空闲/模拟退款/广播释放事件→候补检查由 task-05 编排。 */
    @Override
    public void refund(TicketOrder order) {
        order.setStatus(OrderState.REFUNDED);
    }

    /** 行7：已出票--改签-->已出票（新场次自迁移）；动作=留存改签来源（原座位→空闲/新场次锁座出票由 task-05 编排）。 */
    @Override
    public void changeShowing(TicketOrder order, Long newShowingId) {
        if (newShowingId == null) {
            throw reject(order, "改签必须指定新场次");
        }
        order.setChangeFromShowingId(newShowingId);
    }

    /** 构造"非法迁移拒绝"异常（携带订单与当前状态，便于定位）。 */
    private IllegalStateTransitionException reject(TicketOrder order, String why) {
        return new IllegalStateTransitionException(
                "订单" + String.valueOf(order.getId()) + "当前状态[" + stateEnum() + "]拒绝迁移：" + why);
    }
}
