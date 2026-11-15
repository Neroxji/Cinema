package com.cinema.service.admin.strategy;

import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.SeatZone;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 分区计价策略（具体策略）：黄金区/普通区两档价（IMAX 厅按座位模板分区标注取价）。
 *
 * <p>// @Pattern: Strategy(具体策略：按分区两档价), @UseCase: UC105/204/205
 *
 * <p>参数：{@code {"goldenPrice":45.00,"normalPrice":35.00}}；
 * 两档价必填且 &gt; 0（UC105-3a：分区价≤0 拒绝）。分区标注来源为
 * {@code ImaxSeatFactory} 写入 seat.zone / cinema_hall.zone_rule（UC102/103）。
 */
// @Pattern: Strategy(具体策略), @UseCase: UC105/204/205
@Component
public class ZonePricing implements PriceStrategy {

    /** 参数键：黄金区价。 */
    static final String KEY_GOLDEN_PRICE = "goldenPrice";

    /** 参数键：普通区价。 */
    static final String KEY_NORMAL_PRICE = "normalPrice";

    @Override
    public PriceType type() {
        return PriceType.ZONE_PRICING;
    }

    @Override
    public void validate(JsonNode params) {
        // 两档价均必填且 > 0（UC105-3a：分区价 ≤ 0 拒绝）
        OriginalPrice.requirePositive(params, KEY_GOLDEN_PRICE);
        OriginalPrice.requirePositive(params, KEY_NORMAL_PRICE);
    }

    @Override
    public BigDecimal calculate(SeatZone zone, JsonNode params) {
        String key = zone == SeatZone.GOLDEN ? KEY_GOLDEN_PRICE : KEY_NORMAL_PRICE;
        return params.get(key).decimalValue().setScale(2, RoundingMode.HALF_UP);
    }
}
