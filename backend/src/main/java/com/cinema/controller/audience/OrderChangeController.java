package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.audience.dto.ChangeShowingRequest;
import com.cinema.controller.audience.dto.ChangeShowingResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.aftermarket.ChangeShowingService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 改签接口（spec §2 C 段，UC303：改签至同影片其他场次）。
 *
 * <p>// @UseCase: UC303(申请改签——限同影片/开映前 2 小时/每单限 1 次)
 *
 * <p>校验类失败（订单不存在 12004 / 非本人 12005 / 状态不可改签 13010 / 已改签过 13013 /
 * 已过改签截止 13011 / 目标场次不存在 11005 / 目标场次无效 13012 / 锁座并发失败 10005）
 * 由命令执行流程抛出；成功后原场次座位释放事件已同事务广播（候补检查照常触发）。
 */
@RestController
public class OrderChangeController {

    private final ChangeShowingService changeShowingService;

    public OrderChangeController(ChangeShowingService changeShowingService) {
        this.changeShowingService = changeShowingService;
    }

    /** 改签（UC303）：原场次座位释放 + 新场次锁座出票 + change_from_showing_id 留痕。 */
    @PostMapping("/api/orders/{id}/change")
    @RequireRole(UserRole.AUDIENCE)
    public ApiResponse<ChangeShowingResponse> change(@PathVariable Long id,
                                                     @Valid @RequestBody ChangeShowingRequest request,
                                                     HttpSession session) {
        Long userId = SessionContext.currentUserId(session);
        return ApiResponse.ok(ChangeShowingResponse.from(
                changeShowingService.change(id, userId, request.targetShowingId())));
    }
}
