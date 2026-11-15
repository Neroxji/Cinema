package com.cinema.controller.admin;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.controller.admin.dto.ShowingCreateRequest;
import com.cinema.controller.admin.dto.ShowingResponse;
import com.cinema.controller.admin.dto.StrategyResponse;
import com.cinema.controller.admin.dto.StrategyUpdateRequest;
import com.cinema.model.enums.UserRole;
import com.cinema.service.admin.ShowingAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端排片接口（spec §2 A 段后四条，UC103/UC104/UC105 与排片列表查询）。
 *
 * <p>// @UseCase: UC103/UC104/UC105
 *
 * <p>角色：排片员（SCHEDULER，类级 {@link RequireRole}，AuthInterceptor 校验）。
 */
@RestController
@RequestMapping("/api/admin")
@RequireRole(UserRole.SCHEDULER)
public class ShowingAdminController {

    private final ShowingAdminService showingAdminService;

    public ShowingAdminController(ShowingAdminService showingAdminService) {
        this.showingAdminService = showingAdminService;
    }

    /**
     * 创建场次并按模板生成座位（UC103）。
     * 命中扩展点 3a（未配置票价策略）时 message 携带"已默认套用全场原价"提示。
     */
    @PostMapping("/showings")
    public ApiResponse<ShowingResponse> create(@Valid @RequestBody ShowingCreateRequest request) {
        ShowingAdminService.CreateResult result = showingAdminService.create(request);
        String message = result.strategyDefaulted()
                ? "排片成功；未配置票价策略，已默认套用全场原价"
                : "排片成功";
        return new ApiResponse<>(0, message, result.showing());
    }

    /** 取消场次（UC104）：存在已售/锁定座位拒绝（I14）。 */
    @PutMapping("/showings/{id}/cancel")
    public ApiResponse<ShowingResponse> cancel(@PathVariable Long id) {
        return ApiResponse.ok(showingAdminService.cancel(id));
    }

    /** 配置/切换票价策略（UC105）：响应含示例价格预览；即时生效，已出票订单不受影响。 */
    @PutMapping("/showings/{id}/strategy")
    public ApiResponse<StrategyResponse> updateStrategy(@PathVariable Long id,
                                                        @Valid @RequestBody StrategyUpdateRequest request) {
        return ApiResponse.ok(showingAdminService.updateStrategy(id, request));
    }

    /** 排片列表查询（UC103/104）：按开映时间升序；movieId/hallId/status 可选过滤。 */
    @GetMapping("/showings")
    public ApiResponse<List<ShowingResponse>> list(@RequestParam(required = false) Long movieId,
                                                   @RequestParam(required = false) Long hallId,
                                                   @RequestParam(required = false) String status) {
        return ApiResponse.ok(showingAdminService.list(movieId, hallId, status));
    }
}
