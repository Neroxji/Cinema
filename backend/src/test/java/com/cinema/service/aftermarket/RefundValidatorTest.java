package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.enums.OrderState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U7 · 退票规则纯逻辑单测（测试计划 §2 U7，P1 模块账）。
 *
 * <p>被测对象 {@link RefundValidator}（UC302-4/4a/4b 校验器，纯函数无数据库依赖）：
 * 已检票 / 已退票 / 他人订单 / 过开映前 2 小时 → 全部拒绝且原因正确（错误码逐条核对）。
 */
@DisplayName("U7 · 退票规则（UC302-4a/4b 纯逻辑）：全部拒绝且原因正确")
class RefundValidatorTest {

    /** 退票截止（开映前 2 小时，NFR2 默认档）。 */
    private static final Duration DEADLINE = Duration.ofHours(2);

    /** 基准时间：开映前 3 小时（未过截止的合法窗口）。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 10, 0, 0);

    private static final LocalDateTime START = NOW.plusHours(3);

    private static final Long OWNER = 100L;
    private static final Long OTHER = 200L;

    private static BizException thrownBy(OrderState status, Long requesterId,
                                         LocalDateTime start, LocalDateTime now) {
        return assertThrows(BizException.class, () -> RefundValidator.assertRefundable(
                status, OWNER, requesterId, start, now, DEADLINE));
    }

    // =====================================================================
    // U7-1 · 已检票（UC302-4a）：终态拒绝一切变更
    // =====================================================================

    @Test
    @DisplayName("U7-1 已检票订单退票：拒绝 13001，原因指明已检票终态")
    void u7_1_checkedInRejected() {
        BizException e = thrownBy(OrderState.CHECKED_IN, OWNER, START, NOW);
        assertEquals(ErrorCode.ORDER_NOT_REFUNDABLE.getCode(), e.getCode(), "已检票应返回 13001");
        assertTrue(e.getMessage().contains("已检票"), "拒绝原因应指明已检票：" + e.getMessage());
        assertTrue(e.getMessage().contains("终态"), "拒绝原因应指明终态语义：" + e.getMessage());
    }

    // =====================================================================
    // U7-2 · 已退票（UC302-4a）：不可重复退票
    // =====================================================================

    @Test
    @DisplayName("U7-2 已退票订单重复退票：拒绝 13001，原因指明不可重复退")
    void u7_2_refundedRejected() {
        BizException e = thrownBy(OrderState.REFUNDED, OWNER, START, NOW);
        assertEquals(ErrorCode.ORDER_NOT_REFUNDABLE.getCode(), e.getCode(), "已退票应返回 13001");
        assertTrue(e.getMessage().contains("已退票"), "拒绝原因应指明已退票：" + e.getMessage());
    }

    // =====================================================================
    // U7-3 · 他人订单：越权拒绝（12005，先于状态校验）
    // =====================================================================

    @Test
    @DisplayName("U7-3 他人订单退票：拒绝 12005（越权优先于状态/截止校验）")
    void u7_3_forbiddenOrderRejected() {
        BizException e = thrownBy(OrderState.ISSUED, OTHER, START, NOW);
        assertEquals(ErrorCode.ORDER_FORBIDDEN.getCode(), e.getCode(), "非本人应返回 12005");
        assertTrue(e.getMessage().contains("他人"), "拒绝原因应指明越权：" + e.getMessage());

        // 越权优先：即便订单也不可退（已检票）或已过截止，仍按 12005 拒绝（校验顺序归属→状态→截止）
        assertEquals(ErrorCode.ORDER_FORBIDDEN.getCode(),
                thrownBy(OrderState.CHECKED_IN, OTHER, START, NOW).getCode());
        assertEquals(ErrorCode.ORDER_FORBIDDEN.getCode(),
                thrownBy(OrderState.ISSUED, OTHER, START.minusMinutes(1), NOW).getCode());
    }

    // =====================================================================
    // U7-4 · 过开映前 2 小时（UC302-4b）：截止拒绝
    // =====================================================================

    @Test
    @DisplayName("U7-4 已过退票截止（开映前 2 小时内）：拒绝 13002，原因含截止时刻")
    void u7_4_deadlinePassedRejected() {
        // 开映前 1 小时 59 分 59 秒仍可退（窗口内边界）
        LocalDateTime insideWindow = START.minus(DEADLINE).minusSeconds(1);
        RefundValidator.assertRefundable(OrderState.ISSUED, OWNER, OWNER, START, insideWindow, DEADLINE);

        // 恰好到达截止时刻（now == start - 2h）即过截止（UC302-4b）
        BizException atDeadline = thrownBy(OrderState.ISSUED, OWNER, START, START.minus(DEADLINE));
        assertEquals(ErrorCode.REFUND_DEADLINE_PASSED.getCode(), atDeadline.getCode(), "过截止应返回 13002");
        assertTrue(atDeadline.getMessage().contains("退票截止"),
                "拒绝原因应指明退票截止：" + atDeadline.getMessage());
        assertTrue(atDeadline.getMessage().contains("11:00"),
                "拒绝原因应含具体截止时刻（开映前 2 小时推算）：" + atDeadline.getMessage());

        // 已开映更不可退
        assertEquals(ErrorCode.REFUND_DEADLINE_PASSED.getCode(),
                thrownBy(OrderState.ISSUED, OWNER, START, START.plusMinutes(10)).getCode());
    }

    // =====================================================================
    // U7-5 · 其余非已出票状态（待支付/已取消）与合法路径对照
    // =====================================================================

    @Test
    @DisplayName("U7-5 待支付/已取消不可退（13001）；本人+已出票+窗口内校验通过")
    void u7_5_otherStatesAndHappyPath() {
        assertEquals(ErrorCode.ORDER_NOT_REFUNDABLE.getCode(),
                thrownBy(OrderState.PENDING_PAYMENT, OWNER, START, NOW).getCode());
        assertEquals(ErrorCode.ORDER_NOT_REFUNDABLE.getCode(),
                thrownBy(OrderState.CANCELED, OWNER, START, NOW).getCode());

        // 合法路径：本人 + 已出票 + 开映前 2 小时之外 → 校验通过不抛异常
        RefundValidator.assertRefundable(OrderState.ISSUED, OWNER, OWNER, START, NOW, DEADLINE);
    }
}
