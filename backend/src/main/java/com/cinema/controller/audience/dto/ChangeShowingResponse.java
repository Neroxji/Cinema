package com.cinema.controller.audience.dto;

import com.cinema.service.aftermarket.ChangeShowingService;

import java.math.BigDecimal;
import java.util.List;

/**
 * 改签响应（POST /api/orders/{id}/change 成功时的 data，UC303）。
 */
public record ChangeShowingResponse(

        /** 订单 id。 */
        Long orderId,

        /** 订单号。 */
        String orderNo,

        /** 订单状态：保持 ISSUED（已出票，指向新场次）。 */
        String status,

        /** 原场次 id。 */
        Long originalShowingId,

        /** 新场次 id（= changeFromShowingId 留痕值）。 */
        Long newShowingId,

        /** 改签来源场次 id（每单限 1 次的判定依据）。 */
        Long changeFromShowingId,

        /** 新场次座位 id 列表（原场次座位已释放回空闲）。 */
        List<Long> newSeatIds,

        /** 改签后订单总额（按新场次票价策略重算，差价不结算——实现口径见字段表）。 */
        BigDecimal newTotalAmount
) {
    public static ChangeShowingResponse from(ChangeShowingService.ChangeOutcome outcome) {
        return new ChangeShowingResponse(outcome.orderId(), outcome.orderNo(), outcome.status().name(),
                outcome.originalShowingId(), outcome.newShowingId(), outcome.changeFromShowingId(),
                outcome.newSeatIds(), outcome.newAmount());
    }
}
