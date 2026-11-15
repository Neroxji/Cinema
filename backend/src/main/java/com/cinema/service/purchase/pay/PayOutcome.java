package com.cinema.service.purchase.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付结果（UC205 流程出口，service 层直接断言 / controller 层映射为响应与错误码）。
 *
 * @param type       结果类型
 * @param orderId    订单 id
 * @param orderNo    订单号
 * @param pickupCode 取票码（仅 SUCCESS 时非空，8 位数字）
 * @param paidAmount 实付金额 = 明细快照合计（仅 SUCCESS）
 * @param payTime    支付时间（仅 SUCCESS）
 * @param seatIds    出票座位 id（仅 SUCCESS）
 * @param message    结果说明（失败/作废原因，供 controller 提示）
 */
public record PayOutcome(PayOutcomeType type, Long orderId, String orderNo, String pickupCode,
                         BigDecimal paidAmount, LocalDateTime payTime, List<Long> seatIds, String message) {

    /** 结果类型：成功 / 网关失败(UC205-3a) / 支付作废(UC205-4a) / 订单状态不可支付。 */
    public enum PayOutcomeType {
        SUCCESS,
        GATEWAY_FAILED,
        VOIDED,
        NOT_PAYABLE
    }

    public static PayOutcome success(Long orderId, String orderNo, String pickupCode,
                                     BigDecimal paidAmount, LocalDateTime payTime, List<Long> seatIds) {
        return new PayOutcome(PayOutcomeType.SUCCESS, orderId, orderNo, pickupCode,
                paidAmount, payTime, seatIds, "出票成功");
    }

    public static PayOutcome gatewayFailed(Long orderId, String orderNo) {
        return new PayOutcome(PayOutcomeType.GATEWAY_FAILED, orderId, orderNo, null,
                null, null, List.of(), "模拟支付网关返回失败，订单保持待支付，座位维持锁定至倒计时结束");
    }

    public static PayOutcome voided(Long orderId, String orderNo) {
        return new PayOutcome(PayOutcomeType.VOIDED, orderId, orderNo, null,
                null, null, List.of(), "座位锁定已释放，支付已作废并退款，请重新选座");
    }

    public static PayOutcome notPayable(Long orderId, String orderNo, String reason) {
        return new PayOutcome(PayOutcomeType.NOT_PAYABLE, orderId, orderNo, null,
                null, null, List.of(), "订单当前状态不可支付：" + reason);
    }
}
