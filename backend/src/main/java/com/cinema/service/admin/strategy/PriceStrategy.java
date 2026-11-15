package com.cinema.service.admin.strategy;

import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.SeatZone;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * 票价策略接口（策略模式抽象策略，spec §5 模式落点 A 模块）。
 *
 * <p>// @Pattern: Strategy(抽象策略：定义计价与参数校验的统一契约，
 * <br>三档预设实现可互相替换，新增策略不改调用方), @UseCase: UC105/204/205
 *
 * <p>三档预设（UC105-3，参数 JSON 约定与 {@link PriceType} 枚举注释同口径，
 * 由 DataSeeder 种子数据与 task-02 本包实现共用）：
 * <ul>
 *   <li>ORIGINAL       （{@link OriginalPrice}）：{@code {"basePrice":38.00}}</li>
 *   <li>MEMBER_DISCOUNT（{@link MemberDiscount}）：{@code {"basePrice":40.00,"discount":0.8}}，折扣系数 ∈ (0,1) 开区间</li>
 *   <li>ZONE_PRICING   （{@link ZonePricing}）：{@code {"goldenPrice":45.00,"normalPrice":35.00}}，分区价 &gt; 0</li>
 * </ul>
 *
 * <p>即时生效语义（UC105 后置条件）：策略只挂在 showing.price_type / price_params 上，
 * 已出票订单的金额快照在 order_item.price_snapshot，切换策略不回溯影响。
 *
 * <p>金额口径：一律 2 位小数、HALF_UP 舍入（与 DataSeeder 种子快照价一致）。
 */
// @Pattern: Strategy(抽象策略), @UseCase: UC105/204/205
public interface PriceStrategy {

    /** 本策略适配的票价类型（策略选择键）。 */
    PriceType type();

    /**
     * 校验策略参数（UC105-3a：参数非法抛 BizException，code=11003 INVALID_STRATEGY_PARAM）。
     * 创建场次（UC103-3）与切换策略（UC105-5）前都必须先经本校验。
     */
    void validate(JsonNode params);

    /**
     * 计算某座位票价。
     *
     * @param zone   座位分区（分区计价按 GOLDEN/NORMAL 取两档价；全场类策略忽略该参数）
     * @param params 计价参数（须先经 {@link #validate} 校验合法）
     * @return 票价（2 位小数，HALF_UP）
     */
    BigDecimal calculate(SeatZone zone, JsonNode params);
}
