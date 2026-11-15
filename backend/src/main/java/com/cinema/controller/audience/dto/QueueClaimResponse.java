package com.cinema.controller.audience.dto;

import com.cinema.service.aftermarket.QueueService;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 候补确认递补响应（POST /api/queue/{id}/claim 成功时的 data，UC305）。
 */
public record QueueClaimResponse(

        /** 候补记录 id。 */
        Long queueId,

        /** 候补状态：固定 MATCHED（已递补）。 */
        String status,

        /** 递补锁座后创建的待支付订单 id（B 段支付接口路径参数）。 */
        Long orderId,

        /** 待支付订单号。 */
        String orderNo,

        /** 场次 id。 */
        Long showingId,

        /** 递补锁定的座位 id。 */
        Long seatId,

        /** 锁定截止时间（限期内未支付由超时释放任务取消，UC206）。 */
        LocalDateTime lockExpireAt,

        /** 待支付预估金额（按场次策略计价）。 */
        BigDecimal estimatedAmount
) {
    public static QueueClaimResponse from(QueueService.QueueClaimResult result) {
        return new QueueClaimResponse(result.queueId(), result.status().name(), result.orderId(),
                result.orderNo(), result.showingId(), result.seatId(), result.lockExpireAt(),
                result.estimatedAmount());
    }
}
