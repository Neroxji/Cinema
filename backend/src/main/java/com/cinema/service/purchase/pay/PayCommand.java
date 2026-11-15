package com.cinema.service.purchase.pay;

/**
 * 支付命令（模板方法入口参数）。
 *
 * @param orderId 订单 id
 * @param userId  发起支付的观众 user.id（本人校验用，UC205 前置条件）
 */
public record PayCommand(Long orderId, Long userId) {
}
