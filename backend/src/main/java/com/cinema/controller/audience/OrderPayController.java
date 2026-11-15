package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.audience.dto.PayResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.purchase.pay.PayFlowService;
import com.cinema.service.purchase.pay.PayOutcome;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观众端支付接口（spec §2 B 段，UC205：模拟支付并出票）。
 *
 * <p>// @UseCase: UC205(支付出票)+扩展3a/4a/5a
 *
 * <p>结果映射：SUCCESS→0；GATEWAY_FAILED→12001(402)；VOIDED(座位已释放，支付作废)→12002(409)；
 * NOT_PAYABLE→12003(409)。校验类失败（订单不存在/越权）由流程抛 12004/12005。
 */
@RestController
public class OrderPayController {

    private final PayFlowService payFlowService;

    public OrderPayController(PayFlowService payFlowService) {
        this.payFlowService = payFlowService;
    }

    /** 模拟支付并出票（UC205）：仅本人订单（越权 12005），仅观众角色。 */
    @PostMapping("/api/orders/{id}/pay")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<PayResponse> pay(@PathVariable Long id, HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        PayOutcome outcome = payFlowService.pay(id, userId);
        return switch (outcome.type()) {
            case SUCCESS -> ApiResponse.ok(PayResponse.from(outcome));
            case GATEWAY_FAILED -> throw new BizException(ErrorCode.PAY_GATEWAY_FAILED);
            case VOIDED -> throw new BizException(ErrorCode.PAY_VOID_SEAT_RELEASED);
            case NOT_PAYABLE -> throw new BizException(ErrorCode.ORDER_NOT_PAYABLE, outcome.message());
        };
    }
}
