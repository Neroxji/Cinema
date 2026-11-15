package com.cinema.controller.audience.dto;

import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;

/**
 * 座位图座位视图（GET /api/showings/{id}/seats 的 seats 元素，UC203）。
 */
public record SeatView(

        /** 座位 id（锁座请求的 seatIds 取这里）。 */
        Long seatId,

        /** 排号（1 起）。 */
        Integer rowNum,

        /** 座号（1 起）。 */
        Integer colNum,

        /** 分区：GOLDEN 黄金区 / NORMAL 普通区。 */
        SeatZone zone,

        /** 实时状态：FREE 空闲 / LOCKED 锁定 / SOLD 已售。 */
        SeatState status
) {
    public static SeatView from(com.cinema.model.Seat seat) {
        return new SeatView(seat.getId(), seat.getRowNum(), seat.getColNum(),
                seat.getZone(), seat.getStatus());
    }
}
