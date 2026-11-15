package com.cinema.model.enums;

/**
 * 座位状态（3 态），对应 spec §3.2 状态转移表。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 seat.status）：
 * <ul>
 *   <li>FREE   = 空闲</li>
 *   <li>LOCKED = 锁定（含 lock_expire_at / lock_user_id）</li>
 *   <li>SOLD   = 已售</li>
 * </ul>
 *
 * <p>注意：状态机行为接口（SeatStatus 及其实现）由 task-03 实现，本卡仅提供持久化枚举。
 */
public enum SeatState {
    /** 空闲：可选座。 */
    FREE,
    /** 锁定：限时锁定中，其他人不可再选。 */
    LOCKED,
    /** 已售：已出票（存在有效 order_item）。 */
    SOLD
}
