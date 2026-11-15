package com.cinema.service.admin.strategy;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.SeatZone;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 全场原价策略（具体策略）：任意座位统一按 basePrice 计价。
 *
 * <p>// @Pattern: Strategy(具体策略：全场一口价), @UseCase: UC105/204/205
 *
 * <p>参数：{@code {"basePrice":38.00}}；basePrice 必填且 &gt; 0（无价场次不可售）。
 * UC103-3a"未配置票价策略默认套用全场原价"即落在本策略。
 */
// @Pattern: Strategy(具体策略), @UseCase: UC105/204/205
@Component
public class OriginalPrice implements PriceStrategy {

    /** 参数键：基础原价。 */
    static final String KEY_BASE_PRICE = "basePrice";

    @Override
    public PriceType type() {
        return PriceType.ORIGINAL;
    }

    @Override
    public void validate(JsonNode params) {
        // basePrice 必填且 > 0（无价场次不可售）
        requirePositive(params, KEY_BASE_PRICE);
    }

    @Override
    public BigDecimal calculate(SeatZone zone, JsonNode params) {
        return money(params.get(KEY_BASE_PRICE).decimalValue());
    }

    /** 取必填且 > 0 的数值参数（shared by 同包策略实现）。 */
    static BigDecimal requirePositive(JsonNode params, String key) {
        if (params == null || !params.has(key) || params.get(key).isNull()) {
            throw new BizException(ErrorCode.INVALID_STRATEGY_PARAM, "票价策略参数非法：" + key + " 缺失");
        }
        BigDecimal value = params.get(key).decimalValue();
        if (value == null || value.signum() <= 0) {
            throw new BizException(ErrorCode.INVALID_STRATEGY_PARAM, "票价策略参数非法：" + key + " 必须 > 0");
        }
        return value;
    }

    /** 金额口径统一：2 位小数、HALF_UP（与 DataSeeder 快照价一致）。 */
    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
