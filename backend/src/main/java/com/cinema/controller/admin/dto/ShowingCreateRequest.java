package com.cinema.controller.admin.dto;

import com.cinema.model.enums.PriceType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 创建场次请求（POST /api/admin/showings，UC103）。
 *
 * <p>策略约定：
 * <ul>
 *   <li>priceType 缺省（未配置票价策略）→ 默认套用全场原价 ORIGINAL 并在 message 中提示
 *       （UC103-3a），此时 priceParams 仍必填（含 basePrice）；</li>
 *   <li>priceType 显式给出 → priceParams 必填且需通过该策略的参数校验（UC105-3a 同口径，
 *       非法返回 11003）。</li>
 * </ul>
 *
 * <p>startTime 格式 ISO-8601：{@code 2026-11-20T18:30:00}。
 */
public record ShowingCreateRequest(

        /** 影片 id（必填；影片须为 ON_SHOW，否则 11007 终止创建）。 */
        @NotNull(message = "影片 id 不能为空")
        Long movieId,

        /** 影厅 id（必填；座位模板来源）。 */
        @NotNull(message = "影厅 id 不能为空")
        Long hallId,

        /** 开映时间（必填，ISO-8601：yyyy-MM-dd'T'HH:mm:ss）。 */
        @NotNull(message = "开映时间不能为空")
        LocalDateTime startTime,

        /** 票价策略类型（可选）：ORIGINAL / MEMBER_DISCOUNT / ZONE_PRICING；缺省默认 ORIGINAL。 */
        PriceType priceType,

        /** 计价参数（必填对象，键约定见 PriceType 枚举注释与字段表）。 */
        JsonNode priceParams
) {
}
