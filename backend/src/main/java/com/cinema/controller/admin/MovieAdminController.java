package com.cinema.controller.admin;

import com.cinema.common.ApiResponse;
import com.cinema.common.annotation.RequireRole;
import com.cinema.controller.admin.dto.MovieCreateRequest;
import com.cinema.controller.admin.dto.MovieResponse;
import com.cinema.controller.admin.dto.MovieUpdateRequest;
import com.cinema.model.enums.UserRole;
import com.cinema.service.admin.MovieAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端影片接口（spec §2 A 段前两条，UC101/UC104）。
 *
 * <p>// @UseCase: UC101/UC104
 *
 * <p>角色：排片员（SCHEDULER，类级 {@link RequireRole}，AuthInterceptor 校验）。
 */
@RestController
@RequestMapping("/api/admin")
@RequireRole(UserRole.SCHEDULER)
public class MovieAdminController {

    private final MovieAdminService movieAdminService;

    public MovieAdminController(MovieAdminService movieAdminService) {
        this.movieAdminService = movieAdminService;
    }

    /** 新增并上架影片（UC101）：创建即 ON_SHOW。 */
    @PostMapping("/movies")
    public ApiResponse<MovieResponse> create(@Valid @RequestBody MovieCreateRequest request) {
        return ApiResponse.ok(movieAdminService.create(request));
    }

    /** 编辑影片信息或上下架（UC104）：部分更新，仅非 null 字段生效。 */
    @PutMapping("/movies/{id}")
    public ApiResponse<MovieResponse> update(@PathVariable Long id,
                                             @Valid @RequestBody MovieUpdateRequest request) {
        return ApiResponse.ok(movieAdminService.update(id, request));
    }
}
