package com.cinema.common.event;

import java.time.LocalDateTime;

/**
 * 递补成功事件（UC305：候补观众在响应时限内确认，直接锁定释放的座位）。
 *
 * <p>// @Pattern: Observer(领域事件消息体), @UseCase: UC305 / UC306
 *
 * <p>订阅者语义（spec §7.7 事件清单）：通知订阅者写站内通知（UC306）。
 *
 * @param queueId   候补记录 id（queue 表主键）
 * @param orderId   递补后生成的订单 id（可空：递补确认时序由 task-05 决定）
 * @param showingId 场次 id
 * @param seatId    递补锁定的座位 id（单座语义，UC305）
 * @param userId    递补成功的候补观众 id
 * @param occurredAt 事件发生时间
 */
// @Pattern: Observer(领域事件消息体), @UseCase: UC305/UC306
public record QueueMatchedEvent(Long queueId, Long orderId, Long showingId, Long seatId,
                                Long userId, LocalDateTime occurredAt) {
}
