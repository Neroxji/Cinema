package com.cinema.controller.admin;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.common.web.SessionContext;
import com.cinema.controller.admin.dto.CheckinRequest;
import com.cinema.controller.admin.dto.CheckinResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.aftermarket.CheckinService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检票核销接口（spec §2 C 段管理端，UC307：检票核销，幂等）。
 *
 * <p>// @UseCase: UC307(检票核销)+扩展3a/3b/5a, @UseCase: A8(重复核销并发仅一次生效)
 *
 * <p>仅检票员角色（CHECKER，其他角色 10002）。失败映射：10003 凭据缺失 /
 * 12004 订单不存在（凭据检索不到）/ 13008 已检票重复核销（幂等拒绝）/ 13007 状态不可核销 /
 * 13009 开映超 30 分钟转人工（3b）。操作员记录：响应返回 + 应用日志（编排者契约决策 2）。
 */
@RestController
public class CheckinAdminController {

    private final CheckinService checkinService;

    public CheckinAdminController(CheckinService checkinService) {
        this.checkinService = checkinService;
    }

    /** 检票核销（UC307）：凭订单号或取票码检索，仅 ISSUED 可核销；CHECKED_IN 终态；并发仅一次生效。 */
    @PostMapping("/api/admin/checkin")
    @RequireRole(UserRole.CHECKER)
    public ApiResponse<CheckinResponse> checkin(@RequestBody(required = false) CheckinRequest request,
                                                HttpSession session) {
        CheckinRequest body = request == null ? new CheckinRequest(null, null) : request;
        String operator = SessionContext.currentUsername(session);
        return ApiResponse.ok(CheckinResponse.from(
                checkinService.checkin(body.orderNo(), body.pickupCode(), operator)));
    }
}
