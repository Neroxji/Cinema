package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.audience.dto.RefundResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.aftermarket.RefundService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退票接口（spec §2 C 段，UC302：退票并触发候补递补检查）。
 *
 * <p>// @UseCase: UC302(申请退票)+扩展4a/4b/6a/6b
 *
 * <p>校验类失败（订单不存在 12004 / 非本人 12005 / 状态不可退 13001 / 已过退票截止 13002）
 * 由命令执行流程抛出，统一响应外壳返回；成功后座位释放事件已同事务广播
 * （候补队列非空时第一位将收到递补通知 UC302-6a，队列空则座位直接可售 6b）。
 */
@RestController
public class OrderRefundController {

    private final RefundService refundService;

    public OrderRefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    /** 退票（UC302）：仅本人已出票未检票订单，未过开映前 2 小时（4b）。请求体可为空 JSON {}。 */
    @PostMapping("/api/orders/{id}/refund")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<RefundResponse> refund(@PathVariable Long id, HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        return ApiResponse.ok(RefundResponse.from(refundService.refund(id, userId)));
    }
}
