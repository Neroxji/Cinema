package com.cinema.service.admin.strategy;

import com.cinema.common.BizException;
import com.cinema.model.Seat;
import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.SeatZone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4 · 票价策略计价测试（测试计划 U4，UC105）：同一座位三种计价金额正确（原价/8 折/分区）。
 *
 * <p>纯单元测试：直接 new 策略对象与座位实例，不起 Spring 容器、不连数据库。
 * 金额口径与 DataSeeder 种子快照价一致（2 位小数、HALF_UP）。
 */
@DisplayName("U4 PriceStrategy：同一座位三种计价金额正确（原价/8 折/分区）")
class PriceStrategyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OriginalPrice originalPrice = new OriginalPrice();
    private final MemberDiscount memberDiscount = new MemberDiscount();
    private final ZonePricing zonePricing = new ZonePricing();

    /** 构造指定分区的座位实例（只用到 zone，无需持久化）。 */
    private Seat seatOf(SeatZone zone) {
        Seat seat = new Seat();
        seat.setZone(zone);
        return seat;
    }

    private JsonNode params(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    @DisplayName("同一黄金区座位：原价 38.00 / 8 折 32.00（40×0.8）/ 分区 45.00")
    void sameGoldenSeatThreeStrategies() throws Exception {
        Seat goldenSeat = seatOf(SeatZone.GOLDEN);

        BigDecimal original = originalPrice.calculate(goldenSeat.getZone(),
                params("{\"basePrice\":38}"));
        BigDecimal member = memberDiscount.calculate(goldenSeat.getZone(),
                params("{\"basePrice\":40,\"discount\":0.8}"));
        BigDecimal zone = zonePricing.calculate(goldenSeat.getZone(),
                params("{\"goldenPrice\":45,\"normalPrice\":35}"));

        assertEquals(0, new BigDecimal("38.00").compareTo(original), "原价应为 38.00");
        assertEquals(0, new BigDecimal("32.00").compareTo(member), "会员 8 折应为 40×0.8=32.00");
        assertEquals(0, new BigDecimal("45.00").compareTo(zone), "分区计价黄金区应为 45.00");
        // 金额口径：一律 2 位小数
        assertEquals(2, original.scale());
        assertEquals(2, member.scale());
        assertEquals(2, zone.scale());
    }

    @Test
    @DisplayName("同一普通区座位：原价 38.00 / 8 折 32.00 / 分区 35.00")
    void sameNormalSeatThreeStrategies() throws Exception {
        Seat normalSeat = seatOf(SeatZone.NORMAL);

        BigDecimal original = originalPrice.calculate(normalSeat.getZone(),
                params("{\"basePrice\":38}"));
        BigDecimal member = memberDiscount.calculate(normalSeat.getZone(),
                params("{\"basePrice\":40,\"discount\":0.8}"));
        BigDecimal zone = zonePricing.calculate(normalSeat.getZone(),
                params("{\"goldenPrice\":45,\"normalPrice\":35}"));

        assertEquals(0, new BigDecimal("38.00").compareTo(original));
        assertEquals(0, new BigDecimal("32.00").compareTo(member));
        assertEquals(0, new BigDecimal("35.00").compareTo(zone), "分区计价普通区应为 35.00");
    }

    @Test
    @DisplayName("舍入口径：40.55×0.85=34.4675 → HALF_UP 34.47（2 位小数）")
    void memberDiscountRoundingHalfUp() throws Exception {
        BigDecimal price = memberDiscount.calculate(SeatZone.NORMAL,
                params("{\"basePrice\":40.55,\"discount\":0.85}"));
        assertEquals(0, new BigDecimal("34.47").compareTo(price));
        assertEquals(2, price.scale());
    }

    @Test
    @DisplayName("全场类策略忽略分区：原价与 8 折对 GOLDEN/NORMAL 同价")
    void flatStrategiesIgnoreZone() throws Exception {
        JsonNode originalParams = params("{\"basePrice\":38}");
        JsonNode memberParams = params("{\"basePrice\":40,\"discount\":0.8}");
        assertEquals(originalPrice.calculate(SeatZone.GOLDEN, originalParams),
                originalPrice.calculate(SeatZone.NORMAL, originalParams));
        assertEquals(memberDiscount.calculate(SeatZone.GOLDEN, memberParams),
                memberDiscount.calculate(SeatZone.NORMAL, memberParams));
    }

    @Test
    @DisplayName("策略类型与解析器：三档类型映射正确，未知/空类型抛 INVALID_PARAM(10003)")
    void resolverDispatchesByType() {
        PriceStrategyResolver resolver =
                new PriceStrategyResolver(List.of(originalPrice, memberDiscount, zonePricing));

        assertEquals(PriceType.ORIGINAL, resolver.of(PriceType.ORIGINAL).type());
        assertEquals(PriceType.MEMBER_DISCOUNT, resolver.of(PriceType.MEMBER_DISCOUNT).type());
        assertEquals(PriceType.ZONE_PRICING, resolver.of(PriceType.ZONE_PRICING).type());
        assertTrue(resolver.of(PriceType.ORIGINAL) instanceof OriginalPrice);
        assertTrue(resolver.of(PriceType.MEMBER_DISCOUNT) instanceof MemberDiscount);
        assertTrue(resolver.of(PriceType.ZONE_PRICING) instanceof ZonePricing);

        BizException nullType = assertThrows(BizException.class, () -> resolver.of(null));
        assertEquals(10003, nullType.getCode());
    }
}
