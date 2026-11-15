package com.cinema.controller.audience.dto;

import com.cinema.service.aftermarket.QueueService;

/**
 * 加入候补响应（POST /api/showings/{id}/queue 成功时的 data，UC304）。
 */
public record QueueJoinResponse(

        /** 候补记录 id（递补确认接口 POST /api/queue/{id}/claim 的路径参数）。 */
        Long queueId,

        /** 场次 id。 */
        Long showingId,

        /** 候补序号（rank 越小越优先递补）。 */
        Integer rank,

        /** 候补状态：固定 WAITING（排队中）。 */
        String status
) {
    public static QueueJoinResponse from(QueueService.QueueJoinResult result) {
        return new QueueJoinResponse(result.queueId(), result.showingId(), result.rank(),
                result.status().name());
    }
}
