package com.cinema.controller.admin.dto;

import com.cinema.service.aftermarket.CheckinService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检票核销响应（POST /api/admin/checkin 成功时的 data，UC307）。
 */
public record CheckinResponse(

        /** 订单 id。 */
        Long orderId,

        /** 订单号。 */
        String orderNo,

        /** 取票码。 */
        String pickupCode,

        /** 订单状态：固定 CHECKED_IN（已检票，终态）。 */
        String status,

        /** 核销时间（checkin_time 落库值）。 */
        LocalDateTime checkinTime,

        /** 核销操作员用户名（编排者契约决策 2：无 operator 列，写入应用日志并在响应返回）。 */
        String operator,

        /** 场次 id。 */
        Long showingId,

        /** 片名。 */
        String movieTitle,

        /** 影厅名。 */
        String hallName,

        /** 开映时间。 */
        LocalDateTime startTime,

        /** 票券座位明细（排/座/分区/快照价）。 */
        List<SeatView> seats
) {

    /** 座位视图。 */
    public record SeatView(Long seatId, Integer rowNum, Integer colNum, String zone, BigDecimal price) {
    }

    public static CheckinResponse from(CheckinService.CheckinOutcome outcome) {
        return new CheckinResponse(outcome.orderId(), outcome.orderNo(), outcome.pickupCode(),
                outcome.status(), outcome.checkinTime(), outcome.operator(), outcome.showingId(),
                outcome.movieTitle(), outcome.hallName(), outcome.startTime(),
                outcome.seats().stream()
                        .map(s -> new SeatView(s.seatId(), s.rowNum(), s.colNum(), s.zone().name(), s.price()))
                        .toList());
    }
}
