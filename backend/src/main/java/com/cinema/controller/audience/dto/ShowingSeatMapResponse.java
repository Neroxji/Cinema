package com.cinema.controller.audience.dto;

import com.cinema.model.Showing;
import com.cinema.model.enums.PriceType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 场次座位图响应（GET /api/showings/{id}/seats 的 data，UC202/UC203）。
 * 轮询接口（3 秒/次，spec §7.3）：只含座位图所需信息，保持轻量（NFR4）。
 */
public record ShowingSeatMapResponse(

        /** 场次 id。 */
        Long showingId,

        /** 影片 id。 */
        Long movieId,

        /** 片名。 */
        String movieTitle,

        /** 影厅名称。 */
        String hallName,

        /** 开映时间（ISO-8601）。 */
        LocalDateTime startTime,

        /** 场次状态：PUBLISHED / CANCELED / FINISHED。 */
        String status,

        /** 票价策略类型：ORIGINAL / MEMBER_DISCOUNT / ZONE_PRICING。 */
        PriceType priceType,

        /** 计价参数（对象形式，键约定见 PriceType 注释）。 */
        JsonNode priceParams,

        /** 服务端锁定时长秒数（前端倒计时仅展示用）。 */
        long lockSeconds,

        /** 座位总数。 */
        int totalSeats,

        /** 空闲座位数。 */
        int freeSeats,

        /** 锁定中座位数。 */
        int lockedSeats,

        /** 已售座位数。 */
        int soldSeats,

        /** 座位列表（排号升序、座号升序）。 */
        java.util.List<SeatView> seats
) {
    public static ShowingSeatMapResponse of(Showing showing, String hallName, JsonNode priceParams,
                                            long lockSeconds, java.util.List<SeatView> seats) {
        int free = 0;
        int locked = 0;
        int sold = 0;
        for (SeatView seat : seats) {
            switch (seat.status()) {
                case FREE -> free++;
                case LOCKED -> locked++;
                case SOLD -> sold++;
            }
        }
        return new ShowingSeatMapResponse(showing.getId(), showing.getMovie().getId(),
                showing.getMovie().getTitle(), hallName, showing.getStartTime(),
                showing.getStatus().name(), showing.getPriceType(), priceParams,
                lockSeconds, seats.size(), free, locked, sold, seats);
    }
}
