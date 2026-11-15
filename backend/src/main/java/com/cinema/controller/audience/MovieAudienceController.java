package com.cinema.controller.audience;

import com.cinema.common.ApiResponse;
import com.cinema.controller.audience.dto.MovieBriefResponse;
import com.cinema.service.purchase.AudienceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 观众端影片接口（spec §2 B 段，UC202）。
 *
 * <p>// @UseCase: UC202(浏览影片——仅上映中)
 *
 * <p>角色：已登录即可（观众主用；拦截器统一登录校验，无角色限制）。
 */
@RestController
public class MovieAudienceController {

    private final AudienceQueryService audienceQueryService;

    public MovieAudienceController(AudienceQueryService audienceQueryService) {
        this.audienceQueryService = audienceQueryService;
    }

    /** 查询上映中影片列表（UC202）。 */
    @GetMapping("/api/movies")
    public ApiResponse<List<MovieBriefResponse>> movies() {
        return ApiResponse.ok(audienceQueryService.listOnShowMovies().stream()
                .map(MovieBriefResponse::from)
                .toList());
    }
}
