package com.cinema.controller.audience.dto;

import com.cinema.service.aftermarket.RefundService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 退票响应（POST /api/orders/{id}/refund 成功时的 data，UC302）。
 */
public record RefundResponse(

        /** 订单 id。 */
        Long orderId,

        /** 订单号。 */
        String orderNo,

        /** 订单状态：固定 REFUNDED（已退票，终态）。 */
        String status,

        /** 模拟退款金额（=订单总额，模拟渠道到账）。 */
        BigDecimal refundAmount,

        /** 退票时间。 */
        LocalDateTime refundedAt,

        /** 释放座位 id 列表（座位已回空闲、可重售）。 */
        List<Long> seatIds
) {
    public static RefundResponse from(RefundService.RefundOutcome outcome) {
        return new RefundResponse(outcome.orderId(), outcome.orderNo(), outcome.status().name(),
                outcome.refundAmount(), outcome.refundedAt(), outcome.seatIds());
    }
}
