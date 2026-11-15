package com.cinema.service.admin.strategy;

import com.cinema.common.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * U6 · 策略参数校验测试（测试计划 U6，UC105-3a）：
 * 折扣系数∉(0,1)、分区价≤0 → 拒绝（11003 INVALID_STRATEGY_PARAM）。
 *
 * <p>纯单元测试：直接 new 策略对象，不起 Spring 容器、不连数据库。
 */
@DisplayName("U6 策略参数校验：折扣系数∉(0,1)、分区价≤0 → 拒绝(11003)")
class StrategyParamTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final MemberDiscount memberDiscount = new MemberDiscount();
    private final ZonePricing zonePricing = new ZonePricing();
    private final OriginalPrice originalPrice = new OriginalPrice();

    private JsonNode params(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private void assertRejected(Executable validation, String hint) {
        BizException ex = assertThrows(BizException.class, validation, hint);
        assertEquals(11003, ex.getCode(), "参数非法应返回 11003 INVALID_STRATEGY_PARAM：" + hint);
    }

    // ---------- 会员折扣：折扣系数 ∉ (0,1) 一律拒绝 ----------

    @Test
    @DisplayName("折扣系数边界与越界：0 / 1 / 1.2 / -0.2 拒绝，0.01 / 0.99 / 0.8 通过")
    void discountOutOfOpenIntervalRejected() throws Exception {
        assertRejected(() -> memberDiscount.validate(params("{\"basePrice\":40,\"discount\":0}")),
                "discount=0（左边界，开区间外）");
        assertRejected(() -> memberDiscount.validate(params("{\"basePrice\":40,\"discount\":1}")),
                "discount=1（右边界，开区间外）");
        assertRejected(() -> memberDiscount.validate(params("{\"basePrice\":40,\"discount\":1.2}")),
                "discount>1");
        assertRejected(() -> memberDiscount.validate(params("{\"basePrice\":40,\"discount\":-0.2}")),
                "discount<0");
        assertRejected(() -> memberDiscount.validate(params("{\"basePrice\":40}")),
                "discount 缺失");

        assertDoesNotThrow(() -> memberDiscount.validate(params("{\"basePrice\":40,\"discount\":0.01}")),
                "开区间内小值应通过");
        assertDoesNotThrow(() -> memberDiscount.validate(params("{\"basePrice\":40,\"discount\":0.99}")),
                "开区间内大值应通过");
        assertDoesNotThrow(() -> memberDiscount.validate(params("{\"basePrice\":40,\"discount\":0.8}")),
                "8 折预设应通过");
    }

    @Test
    @DisplayName("会员折扣：basePrice 缺失或 ≤0 拒绝")
    void memberBasePriceInvalidRejected() throws Exception {
        assertRejected(() -> memberDiscount.validate(params("{\"discount\":0.8}")),
                "basePrice 缺失");
        assertRejected(() -> memberDiscount.validate(params("{\"basePrice\":0,\"discount\":0.8}")),
                "basePrice=0");
        assertRejected(() -> memberDiscount.validate(params("{\"basePrice\":-40,\"discount\":0.8}")),
                "basePrice<0");
    }

    // ---------- 分区计价：任一档分区价 ≤0 拒绝 ----------

    @Test
    @DisplayName("分区价 ≤0 或缺失：goldenPrice 0 / normalPrice -5 / 缺失 拒绝，合法双档通过")
    void zonePriceNonPositiveRejected() throws Exception {
        assertRejected(() -> zonePricing.validate(params("{\"goldenPrice\":0,\"normalPrice\":35}")),
                "goldenPrice=0");
        assertRejected(() -> zonePricing.validate(params("{\"goldenPrice\":45,\"normalPrice\":-5}")),
                "normalPrice<0");
        assertRejected(() -> zonePricing.validate(params("{\"normalPrice\":35}")),
                "goldenPrice 缺失");
        assertRejected(() -> zonePricing.validate(params("{\"goldenPrice\":45}")),
                "normalPrice 缺失");

        assertDoesNotThrow(() -> zonePricing.validate(params("{\"goldenPrice\":45,\"normalPrice\":35}")),
                "合法双档价应通过");
    }

    // ---------- 全场原价：basePrice 必填且 >0 ----------

    @Test
    @DisplayName("全场原价：basePrice 缺失 / 0 / 负数拒绝，合法通过")
    void originalBasePriceInvalidRejected() throws Exception {
        assertRejected(() -> originalPrice.validate(params("{}")), "basePrice 缺失");
        assertRejected(() -> originalPrice.validate(params("{\"basePrice\":0}")), "basePrice=0");
        assertRejected(() -> originalPrice.validate(params("{\"basePrice\":-38}")), "basePrice<0");
        assertDoesNotThrow(() -> originalPrice.validate(params("{\"basePrice\":38}")), "合法原价应通过");
    }

    // ---------- 空参数对象兜底 ----------

    @Test
    @DisplayName("空对象/空数组参数：三档策略均拒绝")
    void emptyParamsRejected() throws Exception {
        assertRejected(() -> originalPrice.validate(params("{}")), "原价空参数");
        assertRejected(() -> memberDiscount.validate(params("{}")), "折扣空参数");
        assertRejected(() -> zonePricing.validate(params("{}")), "分区空参数");
    }
}
