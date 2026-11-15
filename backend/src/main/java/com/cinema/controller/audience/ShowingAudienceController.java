package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.audience.dto.LockOrderResponse;
import com.cinema.controller.audience.dto.SeatLockRequest;
import com.cinema.controller.audience.dto.ShowingSeatMapResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.purchase.AudienceQueryService;
import com.cinema.service.purchase.TicketBookingService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观众端场次座位接口（spec §2 B 段，UC202/UC203/UC204）。
 *
 * <p>// @UseCase: UC202/203(场次详情+实时座位图轮询)/UC204(选座锁座——并发核心)
 *
 * <p>座位图（GET）已登录即可（轮询用，NFR4 轻量：两查询）；锁座（POST）仅观众
 * （UC204 参与者=观众，{@link RequireRole} 校验）。
 */
@RestController
public class ShowingAudienceController {

    private static final Logger log = LoggerFactory.getLogger(ShowingAudienceController.class);

    private final AudienceQueryService audienceQueryService;
    private final TicketBookingService ticketBookingService;

    public ShowingAudienceController(AudienceQueryService audienceQueryService,
                                     TicketBookingService ticketBookingService) {
        this.audienceQueryService = audienceQueryService;
        this.ticketBookingService = ticketBookingService;
    }

    /** 场次详情 + 实时座位图（UC202/UC203）：轮询接口，3 秒/次（事务内组装，防懒加载越界）。 */
    @GetMapping("/api/showings/{id}/seats")
    public ApiResponse<ShowingSeatMapResponse> seatMap(@PathVariable Long id) {
        return ApiResponse.ok(audienceQueryService.seatMapResponse(id));
    }

    /** 选座锁座（UC204，并发核心接口）：校验→条件更新锁定→创建待支付订单。 */
    @PostMapping("/api/showings/{id}/seats/lock")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<LockOrderResponse> lockSeats(@PathVariable Long id,
                                                    @Valid @RequestBody SeatLockRequest request,
                                                    HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        TicketBookingService.LockOrderResult result =
                ticketBookingService.lockAndCreateOrder(id, userId, request.seatIds());
        log.info("[UC204] 锁座接口：用户 {} 在场次 {} 锁定 {} 座，orderId={}",
                userId, id, request.seatIds().size(), result.orderId());
        return ApiResponse.ok(LockOrderResponse.from(result, audienceQueryService.lockDurationSeconds()));
    }
}
