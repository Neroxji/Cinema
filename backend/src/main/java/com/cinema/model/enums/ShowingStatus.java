package com.cinema.model.enums;

/**
 * 场次状态（按 spec 语义：UC103 发布可售 / UC104 取消）。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 showing.status）：
 * <ul>
 *   <li>PUBLISHED = 已发布（可购/可查，观众端可见）</li>
 *   <li>CANCELED  = 已取消（UC104，已售票场次禁止取消）</li>
 *   <li>FINISHED  = 已结束（预留：放映结束；当前业务由 start_time 推算开映，
 *       后续卡按需启用，避免擅自引入未确认的状态语义）</li>
 * </ul>
 */
public enum ShowingStatus {
    /** 已发布（可售）。 */
    PUBLISHED,
    /** 已取消。 */
    CANCELED,
    /** 已结束（预留，当前业务未强依赖）。 */
    FINISHED
}
