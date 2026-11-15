package com.cinema.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出票成功事件（UC205 支付出票完成）。
 *
 * <p>// @Pattern: Observer(领域事件消息体；由 DomainEventPublisher 发布——发布器与订阅者属后续卡)
 * <br>// @UseCase: UC205 / UC306
 *
 * <p>订阅者语义（spec §7.7 事件清单）：通知订阅者写站内通知（UC306），检票端可见待核销票。
 *
 * @param orderId     订单 id
 * @param orderNo     订单号
 * @param showingId   场次 id
 * @param userId      购票用户 id
 * @param seatIds     本单出票的全部座位 id
 * @param totalAmount 支付总金额
 * @param occurredAt  事件发生时间
 */
// @Pattern: Observer(领域事件消息体), @UseCase: UC205/UC306
public record TicketIssuedEvent(Long orderId, String orderNo, Long showingId, Long userId,
                                List<Long> seatIds, BigDecimal totalAmount, LocalDateTime occurredAt) {
}
