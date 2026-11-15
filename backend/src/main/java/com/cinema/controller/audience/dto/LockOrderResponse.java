package com.cinema.controller.audience.dto;

import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatZone;
import com.cinema.service.purchase.TicketBookingService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 锁座下单响应（POST /api/showings/{id}/seats/lock 的 data，UC204 步骤 5）。
 */
public record LockOrderResponse(

        /** 订单 id（支付接口路径参数）。 */
        Long orderId,

        /** 订单号（B+时间戳+随机，全库唯一）。 */
        String orderNo,

        /** 场次 id。 */
        Long showingId,

        /** 片名（展示）。 */
        String movieTitle,

        /** 订单状态：固定 PENDING_PAYMENT（待支付）。 */
        OrderState status,

        /** 锁定截止时间（前端倒计时截止；过期由服务端释放，前端倒计时仅展示）。 */
        LocalDateTime lockExpireAt,

        /** 服务端锁定时长秒数（倒计时展示）。 */
        long lockSeconds,

        /** 待支付预估金额（=按策略计价的座位单价合计；出票以明细快照为准）。 */
        BigDecimal estimatedAmount,

        /** 座位明细（排/座/分区/预估单价）。 */
        List<SeatPrice> seats
) {

    /** 座位明细视图。 */
    public record SeatPrice(Long seatId, Integer rowNum, Integer colNum, SeatZone zone,
                            BigDecimal price) {
    }

    public static LockOrderResponse from(TicketBookingService.LockOrderResult result, long lockSeconds) {
        List<SeatPrice> seats = result.seats().stream()
                .map(s -> new SeatPrice(s.seatId(), s.rowNum(), s.colNum(), s.zone(), s.price()))
                .toList();
        return new LockOrderResponse(result.orderId(), result.orderNo(), result.showingId(),
                result.showingTitle(), result.status(), result.lockExpireAt(),
                lockSeconds, result.estimatedAmount(), seats);
    }
}
