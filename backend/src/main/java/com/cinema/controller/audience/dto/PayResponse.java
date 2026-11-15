package com.cinema.controller.audience.dto;

import com.cinema.service.purchase.pay.PayOutcome;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 支付出票响应（POST /api/orders/{id}/pay 成功时的 data，UC205）。
 */
public record PayResponse(

        /** 订单 id。 */
        Long orderId,

        /** 订单号。 */
        String orderNo,

        /** 订单状态：ISSUED（已出票）。 */
        String status,

        /** 取票码（8 位数字，检票核销凭据 UC307）。 */
        String pickupCode,

        /** 实付金额（=明细快照合计）。 */
        BigDecimal paidAmount,

        /** 支付时间。 */
        LocalDateTime payTime,

        /** 出票座位 id 列表。 */
        List<Long> seatIds
) {
    public static PayResponse from(PayOutcome outcome) {
        return new PayResponse(outcome.orderId(), outcome.orderNo(), "ISSUED",
                outcome.pickupCode(), outcome.paidAmount(), outcome.payTime(), outcome.seatIds());
    }
}
