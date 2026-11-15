package com.cinema.controller.admin.dto;

import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.ShowingStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 场次响应（POST /api/admin/showings、PUT /{id}/cancel、GET /api/admin/showings 的 data，
 * UC103/104）。GET 列表时返回数组，元素结构与单对象一致。
 */
public record ShowingResponse(

        /** 场次 id。 */
        Long id,

        /** 影片 id。 */
        Long movieId,

        /** 片名（冗余展示字段）。 */
        String movieTitle,

        /** 影厅 id。 */
        Long hallId,

        /** 影厅名称（冗余展示字段）。 */
        String hallName,

        /** 开映时间（ISO-8601：yyyy-MM-dd'T'HH:mm:ss）。 */
        LocalDateTime startTime,

        /** 场次状态：PUBLISHED 已发布 / CANCELED 已取消 / FINISHED 已结束（预留）。 */
        ShowingStatus status,

        /** 票价策略类型：ORIGINAL / MEMBER_DISCOUNT / ZONE_PRICING。 */
        PriceType priceType,

        /** 计价参数（对象形式返回，键约定见 PriceType 枚举注释；无则 null）。 */
        JsonNode priceParams,

        /** 座位总数（按影厅模板生成）。 */
        Integer totalSeats,

        /** 空闲座位数。 */
        Integer freeSeats,

        /** 锁定中座位数。 */
        Integer lockedSeats,

        /** 已售座位数。 */
        Integer soldSeats
) {
}
