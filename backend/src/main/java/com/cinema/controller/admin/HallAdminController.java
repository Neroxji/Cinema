package com.cinema.controller.admin;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.controller.admin.dto.HallCreateRequest;
import com.cinema.controller.admin.dto.HallResponse;
import com.cinema.model.enums.UserRole;
import com.cinema.service.admin.HallAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端影厅接口（spec §2 A 段第三条，UC102）。
 *
 * <p>// @UseCase: UC102/UC103
 *
 * <p>角色：排片员（SCHEDULER，类级 {@link RequireRole}，AuthInterceptor 校验）。
 */
@RestController
@RequestMapping("/api/admin")
@RequireRole(UserRole.SCHEDULER)
public class HallAdminController {

    private final HallAdminService hallAdminService;

    public HallAdminController(HallAdminService hallAdminService) {
        this.hallAdminService = hallAdminService;
    }

    /** 创建影厅与座位模板（UC102）：行列数/分区规则由工厂按 hallType 固定提供。 */
    @PostMapping("/halls")
    public ApiResponse<HallResponse> create(@Valid @RequestBody HallCreateRequest request) {
        return ApiResponse.ok(hallAdminService.create(request));
    }
}
