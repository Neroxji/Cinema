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
 * 会员日折扣策略（具体策略）：全场按 basePrice × discount 计价（会员日 8 折为预设）。
 *
 * <p>// @Pattern: Strategy(具体策略：统一折扣价), @UseCase: UC105/204/205
 *
 * <p>参数：{@code {"basePrice":40.00,"discount":0.8}}；
 * basePrice 必填且 &gt; 0，discount 必填且 ∈ (0,1) 开区间（UC105-3a：∉(0,1) 拒绝）。
 * "会员日 8 折"仅为计价预设，不涉及会员账户体系（PRD 负范围）。
 */
// @Pattern: Strategy(具体策略), @UseCase: UC105/204/205
@Component
public class MemberDiscount implements PriceStrategy {

    /** 参数键：折扣原价（与 {@link OriginalPrice} 同名参数）。 */
    static final String KEY_BASE_PRICE = OriginalPrice.KEY_BASE_PRICE;

    /** 参数键：折扣系数。 */
    static final String KEY_DISCOUNT = "discount";

    @Override
    public PriceType type() {
        return PriceType.MEMBER_DISCOUNT;
    }

    @Override
    public void validate(JsonNode params) {
        OriginalPrice.requirePositive(params, KEY_BASE_PRICE);
        if (params == null || !params.has(KEY_DISCOUNT) || params.get(KEY_DISCOUNT).isNull()) {
            throw new BizException(ErrorCode.INVALID_STRATEGY_PARAM, "票价策略参数非法：" + KEY_DISCOUNT + " 缺失");
        }
        BigDecimal discount = params.get(KEY_DISCOUNT).decimalValue();
        // UC105-3a：折扣系数不在 (0,1) 开区间即拒绝（0 与 1 边界值同样拒绝）
        if (discount.signum() <= 0 || discount.compareTo(BigDecimal.ONE) >= 0) {
            throw new BizException(ErrorCode.INVALID_STRATEGY_PARAM,
                    "票价策略参数非法：折扣系数必须在 (0,1) 区间内");
        }
    }

    @Override
    public BigDecimal calculate(SeatZone zone, JsonNode params) {
        BigDecimal price = params.get(KEY_BASE_PRICE).decimalValue()
                .multiply(params.get(KEY_DISCOUNT).decimalValue());
        return price.setScale(2, RoundingMode.HALF_UP);
    }
}
