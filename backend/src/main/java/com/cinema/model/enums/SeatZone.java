package com.cinema.model.enums;

/**
 * 座位分区（由座位模板工厂按 cinema_hall.zone_rule 写入每个 seat.zone）。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 seat.zone）：
 * <ul>
 *   <li>GOLDEN = 黄金区（IMAX 第 2~4 排，分区计价时执行 goldenPrice）</li>
 *   <li>NORMAL = 普通区（分区计价时执行 normalPrice；普通厅全部为普通区）</li>
 * </ul>
 */
public enum SeatZone {
    /** 黄金区。 */
    GOLDEN,
    /** 普通区。 */
    NORMAL
}
