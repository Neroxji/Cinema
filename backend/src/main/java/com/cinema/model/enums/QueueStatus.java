package com.cinema.model.enums;

/**
 * 候补队列记录状态（按 spec §1 语义：排队/已递补/过期/取消）。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 queue.status）：
 * <ul>
 *   <li>WAITING  = 排队中</li>
 *   <li>MATCHED  = 已递补（UC305 候补观众在响应时限内确认）</li>
 *   <li>EXPIRED  = 已过期（响应时限内未确认，顺延下一位）</li>
 *   <li>CANCELED = 已取消（观众主动取消候补）</li>
 * </ul>
 */
public enum QueueStatus {
    /** 排队中。 */
    WAITING,
    /** 已递补。 */
    MATCHED,
    /** 已过期（超时顺延）。 */
    EXPIRED,
    /** 已取消。 */
    CANCELED
}
