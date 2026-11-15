package com.cinema.model.state;

import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;

import java.time.LocalDateTime;

/**
 * 订单状态机行为接口（State 模式 · State 角色，spec §3.1/§5）。
 *
 * <p>// @Pattern: State(State角色), @UseCase: UC205/302/307
 *
 * <p>每个订单状态对应一个实现类（Pending/Issued/CheckedIn/Refunded/Canceled），Context = TicketOrder：
 * 实体经 {@code statusHandler()} 按当前 status 分派到状态对象，所有迁移必须经状态对象校验合法性，
 * 非法迁移抛 {@link IllegalStateTransitionException}（spec §3.1 行8"状态机拒绝迁移"）。
 *
 * <p>动作挂载约定（spec §3.1"动作"列）：状态对象只负责本实体字段的迁移动作
 * （status / pay_time / checkin_time / change_from_showing_id）；跨聚合动作
 * （座位→已售/空闲、终止倒计时、广播出票/释放事件、模拟退款）由 service 层（task-04/05）
 * 在调用迁移前后编排（观察者事件清单见 spec §7.7），保证状态机纯逻辑可单测（测试计划 U1/U2）。
 */
public interface OrderStatus {

    /** 本状态对应的持久化枚举值（ticket_order.status 存储值）。 */
    OrderState stateEnum();

    /** 支付成功（UC205-3）：待支付 → 已出票，记录支付时间。 */
    void pay(TicketOrder order, LocalDateTime payTime);

    /** 支付失败（UC205-3a）：待支付自环（状态不变，座位维持锁定至倒计时结束）。 */
    void payFailed(TicketOrder order);

    /** 锁定超时（UC206）/ 观众主动放弃（UC204）：待支付 → 已取消。 */
    void cancel(TicketOrder order);

    /** 检票核销（UC307-5）：已出票 → 已检票（终态），记录核销时间。 */
    void checkIn(TicketOrder order, LocalDateTime checkinTime);

    /** 退票（UC302-5）：已出票 → 已退票（终态）。 */
    void refund(TicketOrder order);

    /** 改签（UC303）：已出票自迁移（指向新场次，留存改签来源）。 */
    void changeShowing(TicketOrder order, Long newShowingId);

    /** 按持久化枚举分派到对应状态对象（实现均为无状态单例）。 */
    static OrderStatus of(OrderState state) {
        if (state == null) {
            throw new IllegalStateTransitionException("订单状态缺失，无法分派状态对象");
        }
        return switch (state) {
            case PENDING_PAYMENT -> Pending.instance();
            case ISSUED -> Issued.instance();
            case CHECKED_IN -> CheckedIn.instance();
            case REFUNDED -> Refunded.instance();
            case CANCELED -> Canceled.instance();
        };
    }
}
