package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.audience.dto.NotificationItemResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.aftermarket.NotificationQueryService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知中心接口（spec §2 C 段，UC306：通知中心列表）。
 *
 * <p>// @UseCase: UC306(查看出票/退款/递补等站内通知)
 *
 * <p>仅列表（编排者契约决策 4：20 接口清单之外不加端点）：只读返回本人全部通知（新在前），
 * 不翻转 read_flag；read_flag 语义（无置读入口、恒为未读）见字段表-C模块.md。
 */
@RestController
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    public NotificationController(NotificationQueryService notificationQueryService) {
        this.notificationQueryService = notificationQueryService;
    }

    /** 我的通知列表（UC306）：出票/座位释放/递补/退款四类通知，新通知在前。 */
    @GetMapping("/api/notifications")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<List<NotificationItemResponse>> myNotifications(HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        List<NotificationItemResponse> notices = notificationQueryService.listMyNotifications(userId).stream()
                .map(NotificationItemResponse::from)
                .toList();
        return ApiResponse.ok(notices);
    }
}
