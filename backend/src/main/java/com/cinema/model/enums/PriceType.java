package com.cinema.model.enums;

/**
 * 票价策略类型（三档预设，UC105；不支持自定义区间）。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 showing.price_type）：
 * <ul>
 *   <li>ORIGINAL        = 全场原价</li>
 *   <li>MEMBER_DISCOUNT = 会员日 8 折</li>
 *   <li>ZONE_PRICING    = 分区计价（IMAX 黄金区/普通区两档价）</li>
 * </ul>
 *
 * <p>计价参数以 JSON 存于 showing.price_params，约定（DataSeeder 与 task-02 PriceStrategy
 * 策略实现共用同一口径）：
 * <pre>
 *   ORIGINAL        : {"basePrice":38.00}
 *   MEMBER_DISCOUNT : {"basePrice":40.00,"discount":0.8}   // 折扣系数必须在 (0,1) 区间（UC105-3a）
 *   ZONE_PRICING    : {"goldenPrice":45.00,"normalPrice":35.00}  // 分区价必须 &gt; 0（UC105-3a）
 * </pre>
 */
public enum PriceType {
    /** 全场原价。 */
    ORIGINAL,
    /** 会员日 8 折（计价预设，不涉及会员账户体系）。 */
    MEMBER_DISCOUNT,
    /** 分区计价（黄金区/普通区）。 */
    ZONE_PRICING
}
