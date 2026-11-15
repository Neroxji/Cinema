package com.cinema.common.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 座位释放事件（退票 UC302 / 锁定超时 UC206 / 观众主动放弃 / 改签释放原场次座位）。
 *
 * <p>// @Pattern: Observer(领域事件消息体), @UseCase: UC302 / UC206 / UC305
 *
 * <p>订阅者语义（spec §7.7 事件清单）：候补检查订阅者触发递补（UC305）+ 通知订阅者写站内通知。
 *
 * @param orderId    关联订单 id（锁定超时/主动放弃时可能尚无订单，可空）
 * @param showingId  场次 id
 * @param userId     原占用人 id（可空）
 * @param seatIds    本次释放的座位 id 列表
 * @param reason     释放来源
 * @param occurredAt 事件发生时间
 */
// @Pattern: Observer(领域事件消息体), @UseCase: UC302/UC206/UC305
public record SeatReleasedEvent(Long orderId, Long showingId, Long userId, List<Long> seatIds,
                                ReleaseReason reason, LocalDateTime occurredAt) {

    /** 释放来源（spec §3.2 座位状态表：退票/超时/主动放弃，另含改签释放）。 */
    public enum ReleaseReason {
        /** 退票释放（UC302）。 */
        REFUND,
        /** 锁定超时释放（UC206，含支付与释放并发时支付作废的场景）。 */
        LOCK_TIMEOUT,
        /** 观众主动放弃锁定。 */
        GIVE_UP,
        /** 改签释放原场次座位（UC303）。 */
        CHANGE_SHOWING
    }
}
