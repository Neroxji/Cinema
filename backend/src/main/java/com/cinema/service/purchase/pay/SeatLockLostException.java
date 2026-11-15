package com.cinema.service.purchase.pay;

/**
 * 支付作废内部信号（UC205-4a）：座位已被超时释放/订单已不在待支付态。
 *
 * <p>仅供 {@link PayTxRunner#executePayFlow} 事务边界内部使用：抛出后整个支付事务回滚
 * （无脏数据），由 {@code PayFlowService} 在事务外捕获并走 {@code finalizeVoid} 补记
 * （取消订单/释放残留锁定/模拟退款广播）。
 */
public class SeatLockLostException extends RuntimeException {

    /** 模拟网关是否已扣款成功（决定作废时是否广播退款事件：网关若成功则模拟退款到账）。 */
    private final boolean gatewaySucceeded;

    public SeatLockLostException(boolean gatewaySucceeded, String message) {
        super(message);
        this.gatewaySucceeded = gatewaySucceeded;
    }

    public boolean isGatewaySucceeded() {
        return gatewaySucceeded;
    }
}
