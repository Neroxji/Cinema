package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.audience.dto.OrderSummaryResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.aftermarket.OrderQueryService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 我的订单接口（spec §2 C 段，UC301：查询我的订单列表）。
 *
 * <p>// @UseCase: UC301(查看我的订单——本人数据)
 *
 * <p>仅观众角色（其他角色 10002）；返回当前会话用户的全部订单（状态/场次/座位/金额/取票码），
 * 新单在前，无请求参数。
 */
@RestController
public class OrderQueryController {

    private final OrderQueryService orderQueryService;

    public OrderQueryController(OrderQueryService orderQueryService) {
        this.orderQueryService = orderQueryService;
    }

    /** 我的订单列表（UC301）：本人订单，含状态/场次/座位/金额/取票码。 */
    @GetMapping("/api/orders")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<List<OrderSummaryResponse>> myOrders(HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        List<OrderSummaryResponse> orders = orderQueryService.listMyOrders(userId).stream()
                .map(OrderSummaryResponse::from)
                .toList();
        return ApiResponse.ok(orders);
    }
}
