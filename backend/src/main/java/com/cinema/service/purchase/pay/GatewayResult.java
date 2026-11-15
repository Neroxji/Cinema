package com.cinema.service.purchase.pay;

/**
 * 模拟支付网关返回（UC205-3）。
 *
 * @param success      网关扣款是否成功
 * @param channelTxnNo 渠道流水号（模拟，失败时 null）
 * @param message      网关提示
 */
public record GatewayResult(boolean success, String channelTxnNo, String message) {

    static GatewayResult success(String channelTxnNo) {
        return new GatewayResult(true, channelTxnNo, "模拟网关扣款成功");
    }

    static GatewayResult failure(String message) {
        return new GatewayResult(false, null, message);
    }
}
