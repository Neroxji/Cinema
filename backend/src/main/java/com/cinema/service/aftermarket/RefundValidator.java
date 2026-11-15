package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.enums.OrderState;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 退票规则校验器（UC302-4/4a/4b，纯逻辑无依赖——测试计划 U7 的被测对象）。
 *
 * <p>// @UseCase: UC302(退票校验), @UseCase: U7(纯逻辑单测：已检票/已退票/他人订单/过截止全部拒绝)
 *
 * <p>规则口径（spec §3.1 行6 / PRD UC302）：
 * <ol>
 *   <li>本人订单：请求者必须是订单归属人，否则 12005（非本人订单，越权）；</li>
 *   <li>状态：仅 ISSUED（已出票且未检票）可退——已检票为终态拒绝一切变更（spec §3.1 行8）、
 *       已退票不可重复退、待支付/已取消不适用退票 → 13001；</li>
 *   <li>退票截止：开映前 {@code refundDeadline}（BookingConfig，默认 2 小时）内停止受理——
 *       {@code now >= start - deadline} 即过截止 → 13002。</li>
 * </ol>
 * 校验顺序：归属 → 状态 → 截止（U7 断言按此逐条核对拒绝原因）。
 */
public final class RefundValidator {

    private RefundValidator() {
    }

    /**
     * 校验订单可退（UC302-4），任一规则不满足即抛业务异常（拒绝且原因可辨）。
     *
     * @param status         订单当前状态
     * @param ownerId        订单归属用户 id
     * @param requesterId    发起退票的当前登录用户 id
     * @param showingStart   所属场次开映时间
     * @param now            当前时间（参数化：纯逻辑可测，不直接取系统时钟）
     * @param refundDeadline 退票截止时长（开映前多少时长停止受理，NFR2 配置项）
     * @throws BizException 12005 非本人 / 13001 状态不可退 / 13002 已过退票截止
     */
    public static void assertRefundable(OrderState status, Long ownerId, Long requesterId,
                                        LocalDateTime showingStart, LocalDateTime now,
                                        Duration refundDeadline) {
        if (ownerId == null || !ownerId.equals(requesterId)) {
            throw new BizException(ErrorCode.ORDER_FORBIDDEN, "无权退票他人订单");
        }
        if (status != OrderState.ISSUED) {
            throw new BizException(ErrorCode.ORDER_NOT_REFUNDABLE, refundRejectReason(status));
        }
        LocalDateTime deadline = showingStart.minus(refundDeadline);
        if (!now.isBefore(deadline)) {
            throw new BizException(ErrorCode.REFUND_DEADLINE_PASSED,
                    "已过退票截止（开映前 " + deadline.toLocalDate() + " " + deadline.toLocalTime()
                            + " 停止受理），不可退票");
        }
    }

    /** 按"不可退状态"给出可辨认的拒绝原因（U7：原因正确）。 */
    private static String refundRejectReason(OrderState status) {
        return switch (status) {
            case CHECKED_IN -> "已检票订单为终态，不可退票（UC302-4a）";
            case REFUNDED -> "订单已退票，不可重复退票（UC302-4a）";
            case PENDING_PAYMENT -> "待支付订单不可退票（请直接放弃锁定或等待超时释放）";
            case CANCELED -> "已取消订单不可退票";
            case ISSUED -> "订单可退"; // 不可达（守卫完整性占位）
        };
    }
}
