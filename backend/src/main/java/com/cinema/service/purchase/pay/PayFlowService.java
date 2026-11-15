package com.cinema.service.purchase.pay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 支付流程服务入口（UC205 对外门面）：编排"主事务 + 作废补记事务"两段边界。
 *
 * <p>// @UseCase: UC205(支付出票), @UseCase: 扩展 3a/4a/5a 的事务编排
 *
 * <p>本类自身无事务注解（薄入口，避免自调用代理失效）；事务边界在 {@link PayTxRunner}。
 */
// @UseCase: UC205
@Service
public class PayFlowService {

    private static final Logger log = LoggerFactory.getLogger(PayFlowService.class);

    private final PayTxRunner payTxRunner;

    public PayFlowService(PayTxRunner payTxRunner) {
        this.payTxRunner = payTxRunner;
    }

    /**
     * 支付（UC205）。
     *
     * @param orderId 订单 id
     * @param userId  当前登录观众（本人校验在流程钩子①）
     * @return 支付结果（SUCCESS / GATEWAY_FAILED / VOIDED / NOT_PAYABLE，controller 据此映射）
     */
    public PayOutcome pay(Long orderId, Long userId) {
        PayCommand command = new PayCommand(orderId, userId);
        try {
            return payTxRunner.executePayFlow(command);
        } catch (SeatLockLostException lost) {
            // UC205-4a：主事务已整体回滚 → 独立补记事务落"作废事实"（取消/释放/模拟退款）
            log.info("[UC205-4a] 支付作废（{}），转入补记事务：orderId={}", lost.getMessage(), orderId);
            return payTxRunner.finalizeVoid(command, lost.isGatewaySucceeded());
        }
    }
}
