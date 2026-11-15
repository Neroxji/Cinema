package com.cinema.model.enums;

/**
 * 影片状态（按 spec/PRD 语义：上架可排片 ↔ 下架）。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 movie.status）：
 * <ul>
 *   <li>ON_SHOW  = 上映中（观众端可见，可创建场次）</li>
 *   <li>OFF_SHOW = 已下架（UC104；创建场次时校验终止，UC103-5a）</li>
 * </ul>
 */
public enum MovieStatus {
    /** 上映中。 */
    ON_SHOW,
    /** 已下架。 */
    OFF_SHOW
}
