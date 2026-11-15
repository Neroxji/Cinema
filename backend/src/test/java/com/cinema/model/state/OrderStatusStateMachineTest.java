package com.cinema.model.state;

import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * U1/U2 · 订单状态机单元测试（纯逻辑，不碰数据库——测试计划 §2 U1/U2，P0 护栏）。
 *
 * <p>逐行覆盖 spec §3.1 订单状态转移表：
 * <ul>
 *   <li>U1：合法迁移逐条执行，迁移后状态与动作（pay_time / checkin_time / change_from_showing_id）正确</li>
 *   <li>U2：非法迁移（已检票后退票、待支付直接核销等）抛 {@link IllegalStateTransitionException}，且状态保持不变</li>
 * </ul>
 *
 * <p>Context = {@link TicketOrder}（task-03 追加的状态委托方法），迁移一律经 Context 委托进入
 * 状态对象，分派与守卫（终态拒绝一切）随之被覆盖。固定时间戳保证确定性断言。
 */
@DisplayName("U1/U2 · 订单状态机（spec §3.1，State 模式 Context=TicketOrder）")
class OrderStatusStateMachineTest {

    /** 固定支付时间（确定性断言，不依赖当前时钟）。 */
    private static final LocalDateTime T_PAY = LocalDateTime.of(2026, 1, 1, 12, 0);

    /** 固定核销时间（确定性断言，不依赖当前时钟）。 */
    private static final LocalDateTime T_CHECKIN = LocalDateTime.of(2026, 1, 1, 14, 30);

    /** 构造指定状态的订单（纯逻辑新建实例，不入库）。 */
    private TicketOrder orderOf(OrderState state) {
        TicketOrder order = new TicketOrder();
        order.setStatus(state);
        return order;
    }

    // =====================================================================
    // U1 · 合法迁移逐条（spec §3.1 表行 1~7）
    // =====================================================================

    @Test
    @DisplayName("U1-1 待支付--支付成功-->已出票：记录支付时间（UC205 行1）")
    void u1_pending_paySuccess_issuesAndRecordsPayTime() {
        TicketOrder order = orderOf(OrderState.PENDING_PAYMENT);
        order.pay(T_PAY);
        assertEquals(OrderState.ISSUED, order.getStatus(), "支付成功应迁移至已出票");
        assertEquals(T_PAY, order.getPayTime(), "已出票应记录支付（出票）时间");
    }

    @Test
    @DisplayName("U1-2 待支付--支付失败-->待支付（自环）：状态不变（UC205-3a 行2）")
    void u1_pending_payFailed_staysPending() {
        TicketOrder order = orderOf(OrderState.PENDING_PAYMENT);
        order.payFailed();
        assertEquals(OrderState.PENDING_PAYMENT, order.getStatus(), "支付失败应保持待支付");
        assertNull(order.getPayTime(), "支付失败不应产生支付时间");
    }

    @Test
    @DisplayName("U1-3 待支付--锁定超时-->已取消（UC206 行3）")
    void u1_pending_cancelByLockTimeout_cancels() {
        TicketOrder order = orderOf(OrderState.PENDING_PAYMENT);
        order.cancel();
        assertEquals(OrderState.CANCELED, order.getStatus(), "锁定超时应迁移至已取消");
    }

    @Test
    @DisplayName("U1-4 待支付--观众主动放弃-->已取消（UC204 行4，与超时同事件方法）")
    void u1_pending_cancelByAbandon_cancels() {
        TicketOrder order = orderOf(OrderState.PENDING_PAYMENT);
        order.cancel();
        assertEquals(OrderState.CANCELED, order.getStatus(), "主动放弃应迁移至已取消");
    }

    @Test
    @DisplayName("U1-5 已出票--检票核销-->已检票：记录核销时间（UC307 行5）")
    void u1_issued_checkIn_checksInAndRecordsTime() {
        TicketOrder order = orderOf(OrderState.ISSUED);
        order.checkIn(T_CHECKIN);
        assertEquals(OrderState.CHECKED_IN, order.getStatus(), "核销应迁移至已检票");
        assertEquals(T_CHECKIN, order.getCheckinTime(), "已检票应记录核销时间");
    }

    @Test
    @DisplayName("U1-6 已出票--退票-->已退票（UC302 行6）")
    void u1_issued_refund_refunds() {
        TicketOrder order = orderOf(OrderState.ISSUED);
        order.refund();
        assertEquals(OrderState.REFUNDED, order.getStatus(), "退票应迁移至已退票");
    }

    @Test
    @DisplayName("U1-7 已出票--改签-->已出票（新场次自迁移）：留存改签来源（UC303 行7）")
    void u1_issued_changeShowing_selfLoopsAndRecordsFrom() {
        TicketOrder order = orderOf(OrderState.ISSUED);
        order.changeShowing(99L);
        assertEquals(OrderState.ISSUED, order.getStatus(), "改签为自迁移，仍应已出票（新场次）");
        assertEquals(99L, order.getChangeFromShowingId(), "改签应留存来源场次 id（砍备忘录，字段承载历史）");
    }

    // =====================================================================
    // U2 · 非法迁移拒绝（spec §3.1 行8"终态拒绝一切" + 各状态未列事件一律拒绝）
    // =====================================================================

    @Test
    @DisplayName("U2-1 已检票后退票：拒绝（测试计划 U2 例）")
    void u2_checkedIn_refundRejected() {
        TicketOrder order = orderOf(OrderState.CHECKED_IN);
        assertThrows(IllegalStateTransitionException.class, order::refund, "已检票为终态，退票必须被拒");
        assertEquals(OrderState.CHECKED_IN, order.getStatus(), "拒绝后状态必须不变");
    }

    @Test
    @DisplayName("U2-2 已检票后再支付：拒绝")
    void u2_checkedIn_payRejected() {
        TicketOrder order = orderOf(OrderState.CHECKED_IN);
        assertThrows(IllegalStateTransitionException.class, () -> order.pay(T_PAY));
        assertEquals(OrderState.CHECKED_IN, order.getStatus());
        assertNull(order.getPayTime(), "拒绝后不得写入支付时间");
    }

    @Test
    @DisplayName("U2-3 已检票重复核销：状态机拒绝（UC307-5a 幂等由服务层基于此守卫处理）")
    void u2_checkedIn_checkInAgainRejected() {
        TicketOrder order = orderOf(OrderState.CHECKED_IN);
        assertThrows(IllegalStateTransitionException.class, () -> order.checkIn(T_CHECKIN));
        assertEquals(OrderState.CHECKED_IN, order.getStatus());
    }

    @Test
    @DisplayName("U2-4 已检票改签：拒绝")
    void u2_checkedIn_changeRejected() {
        TicketOrder order = orderOf(OrderState.CHECKED_IN);
        assertThrows(IllegalStateTransitionException.class, () -> order.changeShowing(99L));
        assertEquals(OrderState.CHECKED_IN, order.getStatus());
        assertNull(order.getChangeFromShowingId(), "拒绝后不得写入改签来源");
    }

    @Test
    @DisplayName("U2-5 已退票后核销：拒绝")
    void u2_refunded_checkInRejected() {
        TicketOrder order = orderOf(OrderState.REFUNDED);
        assertThrows(IllegalStateTransitionException.class, () -> order.checkIn(T_CHECKIN));
        assertEquals(OrderState.REFUNDED, order.getStatus());
    }

    @Test
    @DisplayName("U2-6 已退票再退票：拒绝")
    void u2_refunded_refundAgainRejected() {
        TicketOrder order = orderOf(OrderState.REFUNDED);
        assertThrows(IllegalStateTransitionException.class, order::refund);
        assertEquals(OrderState.REFUNDED, order.getStatus());
    }

    @Test
    @DisplayName("U2-7 已取消后支付：拒绝")
    void u2_canceled_payRejected() {
        TicketOrder order = orderOf(OrderState.CANCELED);
        assertThrows(IllegalStateTransitionException.class, () -> order.pay(T_PAY));
        assertEquals(OrderState.CANCELED, order.getStatus());
    }

    @Test
    @DisplayName("U2-8 已取消后退票：拒绝")
    void u2_canceled_refundRejected() {
        TicketOrder order = orderOf(OrderState.CANCELED);
        assertThrows(IllegalStateTransitionException.class, order::refund);
        assertEquals(OrderState.CANCELED, order.getStatus());
    }

    @Test
    @DisplayName("U2-9 待支付直接核销：拒绝（UC307-3a）")
    void u2_pending_checkInRejected() {
        TicketOrder order = orderOf(OrderState.PENDING_PAYMENT);
        assertThrows(IllegalStateTransitionException.class, () -> order.checkIn(T_CHECKIN));
        assertEquals(OrderState.PENDING_PAYMENT, order.getStatus());
        assertNull(order.getCheckinTime(), "拒绝后不得写入核销时间");
    }

    @Test
    @DisplayName("U2-10 待支付退票：拒绝（尚无票可退）")
    void u2_pending_refundRejected() {
        TicketOrder order = orderOf(OrderState.PENDING_PAYMENT);
        assertThrows(IllegalStateTransitionException.class, order::refund);
        assertEquals(OrderState.PENDING_PAYMENT, order.getStatus());
    }

    @Test
    @DisplayName("U2-11 待支付改签：拒绝")
    void u2_pending_changeRejected() {
        TicketOrder order = orderOf(OrderState.PENDING_PAYMENT);
        assertThrows(IllegalStateTransitionException.class, () -> order.changeShowing(99L));
        assertEquals(OrderState.PENDING_PAYMENT, order.getStatus());
    }

    @Test
    @DisplayName("U2-12 已出票重复支付：拒绝（防双付）")
    void u2_issued_payAgainRejected() {
        TicketOrder order = orderOf(OrderState.ISSUED);
        order.setPayTime(T_PAY);
        assertThrows(IllegalStateTransitionException.class, () -> order.pay(T_PAY));
        assertEquals(OrderState.ISSUED, order.getStatus());
    }

    @Test
    @DisplayName("U2-13 已出票收到支付失败事件：拒绝（该事件仅待支付态合法）")
    void u2_issued_payFailedRejected() {
        TicketOrder order = orderOf(OrderState.ISSUED);
        assertThrows(IllegalStateTransitionException.class, order::payFailed);
        assertEquals(OrderState.ISSUED, order.getStatus());
    }

    @Test
    @DisplayName("U2-14 已出票取消（锁定超时/放弃）：拒绝（超时取消仅适用于待支付）")
    void u2_issued_cancelRejected() {
        TicketOrder order = orderOf(OrderState.ISSUED);
        assertThrows(IllegalStateTransitionException.class, order::cancel);
        assertEquals(OrderState.ISSUED, order.getStatus());
    }
}
