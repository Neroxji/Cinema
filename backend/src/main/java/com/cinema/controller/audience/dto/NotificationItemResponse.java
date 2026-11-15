package com.cinema.controller.audience.dto;

import com.cinema.service.aftermarket.NotificationQueryService;

import java.time.LocalDateTime;

/**
 * 通知列表响应元素（GET /api/notifications 的 data 数组项，UC306）。
 */
public record NotificationItemResponse(

        /** 通知 id。 */
        Long id,

        /** 通知类型：TICKET_ISSUED/SEAT_RELEASED/QUEUE_MATCHED/REFUND_COMPLETED。 */
        String type,

        /** 通知标题。 */
        String title,

        /** 通知内容（递补邀请类内嵌"递补凭据Q{queueId}"供 claim 取号）。 */
        String content,

        /** 已读标志（false=未读；本接口只读不翻转，当前无置读入口——语义见字段表）。 */
        boolean readFlag,

        /** 创建时间（递补邀请的截止时间 = created_at + queue-response-limit）。 */
        LocalDateTime createdAt
) {
    public static NotificationItemResponse from(NotificationQueryService.NotificationView view) {
        return new NotificationItemResponse(view.id(), view.type(), view.title(), view.content(),
                view.readFlag(), view.createdAt());
    }
}
