package com.cinema.controller.admin.dto;

/**
 * 检票核销请求体（POST /api/admin/checkin，UC307）。
 *
 * <p>凭订单号<b>或</b>取票码检索（两者至少其一，均传时订单号优先）；请求体可为空 JSON 之外的
 * 携码形态，两字段均缺失时 10003。
 */
public record CheckinRequest(

        /** 订单号（可空；与 pickupCode 至少其一）。 */
        String orderNo,

        /** 取票码（8 位数字，可空；与 orderNo 至少其一）。 */
        String pickupCode
) {
}
