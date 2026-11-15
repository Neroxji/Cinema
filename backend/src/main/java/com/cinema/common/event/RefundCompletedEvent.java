package com.cinema.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款到账事件（UC302 退票：模拟退款到账完成）。
 *
 * <p>// @Pattern: Observer(领域事件消息体), @UseCase: UC302 / UC306
 *
 * <p>订阅者语义（spec §7.7 事件清单）：通知订阅者写站内通知（UC306）。
 * 注意：座位释放另由 {@link SeatReleasedEvent} 承载（候补递补检查挂在释放事件上）。
 *
 * @param orderId      退票订单 id
 * @param orderNo      订单号
 * @param userId       退款接收用户 id
 * @param refundAmount 退款金额（模拟到账，与订单总额一致）
 * @param occurredAt   事件发生时间
 */
// @Pattern: Observer(领域事件消息体), @UseCase: UC302/UC306
public record RefundCompletedEvent(Long orderId, String orderNo, Long userId,
                                   BigDecimal refundAmount, LocalDateTime occurredAt) {
}
