package com.cinema.model.enums;

/**
 * 影厅类型（决定座位模板工厂的选择，spec §5 工厂方法落点）。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 cinema_hall.hall_type）：
 * <ul>
 *   <li>IMAX     = IMAX 厅（模板 5 排 × 10 座 = 50 座，第 2~4 排黄金区）</li>
 *   <li>STANDARD = 普通厅（模板 6 排 × 8 座 = 48 座，不分区）</li>
 * </ul>
 */
public enum HallType {
    /** IMAX 厅。 */
    IMAX,
    /** 普通厅。 */
    STANDARD
}
