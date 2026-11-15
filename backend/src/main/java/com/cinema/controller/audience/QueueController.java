package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.audience.dto.QueueClaimResponse;
import com.cinema.controller.audience.dto.QueueJoinResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.aftermarket.QueueService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候补队列接口（spec §2 C 段，UC304 加入候补 / UC305 候补确认递补并锁座）。
 *
 * <p>// @UseCase: UC304(加入候补——满座场次)/UC305(候补递补——时限内确认锁座)
 *
 * <p>失败映射：13014 场次不满足候补条件（须未开映满座场次）/ 13003 重复候补 /
 * 13004 候补记录不存在或状态不符（未收到递补/已确认/已取消）/ 13005 递补确认超时（顺延下一位）/
 * 13006 递补座位已被其他观众选购 / 11005 场次不存在 / 12005 非本人候补记录。
 */
@RestController
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    /** 加入候补（UC304）：仅未开映的满座场次可候补；重复候补 13003。请求体可为空 JSON {}。 */
    @PostMapping("/api/showings/{id}/queue")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<QueueJoinResponse> join(@PathVariable Long id, HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        return ApiResponse.ok(QueueJoinResponse.from(queueService.join(id, userId)));
    }

    /** 候补确认递补并锁座（UC305）：响应时限内确认 → 锁座 + 待支付订单 + queue→MATCHED。 */
    @PostMapping("/api/queue/{id}/claim")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<QueueClaimResponse> claim(@PathVariable Long id, HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        return ApiResponse.ok(QueueClaimResponse.from(queueService.claim(id, userId)));
    }
}
