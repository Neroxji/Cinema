package com.cinema.controller.audience.dto;

import com.cinema.service.aftermarket.OrderQueryService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 我的订单列表响应元素（GET /api/orders 的 data 数组项，UC301）。
 */
public record OrderSummaryResponse(

        /** 订单 id（退票/改签/支付接口路径参数）。 */
        Long orderId,

        /** 订单号。 */
        String orderNo,

        /** 订单状态：PENDING_PAYMENT/ISSUED/CHECKED_IN/REFUNDED/CANCELED。 */
        String status,

        /** 订单总额（有效明细快照合计；改签后为新场次明细合计）。 */
        BigDecimal totalAmount,

        /** 取票码（8 位数字；待支付订单为 null，检票核销凭据 UC307）。 */
        String pickupCode,

        /** 支付（出票）时间。 */
        LocalDateTime payTime,

        /** 检票核销时间。 */
        LocalDateTime checkinTime,

        /** 改签来源场次 id（仅改签单非空；每单限 1 次的判定依据 UC303）。 */
        Long changeFromShowingId,

        /** 所属场次概要。 */
        ShowingView showing,

        /** 座位明细（含已失效历史明细，valid=false 表示已因退票/改签释放）。 */
        List<SeatItemView> seats
) {

    /** 场次概要。 */
    public record ShowingView(Long showingId, String movieTitle, String hallName, LocalDateTime startTime) {
    }

    /** 座位明细。 */
    public record SeatItemView(Long seatId, Integer rowNum, Integer colNum, String zone,
                               BigDecimal price, boolean valid) {
    }

    public static OrderSummaryResponse from(OrderQueryService.OrderSummary summary) {
        return new OrderSummaryResponse(summary.orderId(), summary.orderNo(), summary.status(),
                summary.totalAmount(), summary.pickupCode(), summary.payTime(), summary.checkinTime(),
                summary.changeFromShowingId(),
                new ShowingView(summary.showing().showingId(), summary.showing().movieTitle(),
                        summary.showing().hallName(), summary.showing().startTime()),
                summary.seats().stream()
                        .map(s -> new SeatItemView(s.seatId(), s.rowNum(), s.colNum(),
                                s.zone().name(), s.price(), s.valid()))
                        .toList());
    }
}
