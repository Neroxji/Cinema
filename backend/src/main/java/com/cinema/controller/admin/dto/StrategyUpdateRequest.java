package com.cinema.controller.admin.dto;

import com.cinema.model.enums.PriceType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/**
 * 配置/切换票价策略请求（PUT /api/admin/showings/{id}/strategy，UC105）。
 *
 * <p>priceType 必填；priceParams 必填对象且需通过该策略的参数校验
 * （UC105-3a：折扣系数∉(0,1) 或分区价≤0 → 11003 拒绝）。
 * 策略保存后即时生效；已出票订单金额为 order_item.price_snapshot 快照，不受影响。
 */
public record StrategyUpdateRequest(

        /** 票价策略类型（必填）：ORIGINAL / MEMBER_DISCOUNT / ZONE_PRICING。 */
        @NotNull(message = "票价策略类型不能为空")
        PriceType priceType,

        /** 计价参数（必填对象，键约定见 PriceType 枚举注释与字段表）。 */
        JsonNode priceParams
) {
}
