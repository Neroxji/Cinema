package com.cinema.model.enums;

/**
 * 订单状态（5 态），对应 spec §3.1 状态转移表。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 ticket_order.status）：
 * <ul>
 *   <li>PENDING_PAYMENT = 待支付</li>
 *   <li>ISSUED          = 已出票</li>
 *   <li>CHECKED_IN      = 已检票（终态）</li>
 *   <li>REFUNDED        = 已退票（终态）</li>
 *   <li>CANCELED        = 已取消（终态）</li>
 * </ul>
 *
 * <p>注意：状态机行为接口（OrderStatus 及其实现）由 task-03 实现，本卡仅提供持久化枚举。
 */
public enum OrderState {
    /** 待支付：锁座后创建订单的初始状态。 */
    PENDING_PAYMENT,
    /** 已出票：支付成功；可检票、可退票、可改签。 */
    ISSUED,
    /** 已检票：核销入场，终态，拒绝一切变更。 */
    CHECKED_IN,
    /** 已退票：退款到账、座位释放，终态。 */
    REFUNDED,
    /** 已取消：锁定超时或观众主动放弃，终态。 */
    CANCELED
}
