package com.cinema.controller.admin.dto;

import com.cinema.model.enums.PriceType;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 票价策略配置响应（PUT /api/admin/showings/{id}/strategy 的 data，UC105）。
 *
 * <p>含策略作用后的示例价格预览（UC105-4"系统展示策略作用后的示例价格预览"）：
 * examplePrices 以分区名（NORMAL/GOLDEN）为键——ZONE_PRICING 返回 NORMAL+GOLDEN 两档，
 * 全场类策略仅 NORMAL 一档。
 */
public record StrategyResponse(

        /** 场次 id。 */
        Long showingId,

        /** 已生效的票价策略类型。 */
        PriceType priceType,

        /** 已生效的计价参数（对象形式）。 */
        JsonNode priceParams,

        /** 示例价格预览（键 NORMAL/GOLDEN → 2 位小数金额；ZONE_PRICING 才有 GOLDEN）。 */
        Map<String, BigDecimal> examplePrices
) {
}
