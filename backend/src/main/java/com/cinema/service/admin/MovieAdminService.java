package com.cinema.service.admin;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.controller.admin.dto.MovieCreateRequest;
import com.cinema.controller.admin.dto.MovieResponse;
import com.cinema.controller.admin.dto.MovieUpdateRequest;
import com.cinema.model.Movie;
import com.cinema.model.enums.MovieStatus;
import com.cinema.repository.MovieRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理端影片服务（UC101 录入并上架 / UC104 编辑信息或上下架）。
 *
 * <p>// @UseCase: UC101/UC104
 *
 * <p>约定：UC101 创建即上架（ON_SHOW，请求不含状态字段）；UC104 为部分更新
 * （仅非 null 字段被更新）；下架（OFF_SHOW）不级联取消既有场次——观众端仅可见
 * ON_SHOW 影片，且 UC103-5a 已在下一次为该影片创建场次时终止。
 */
@Service
public class MovieAdminService {

    private final MovieRepository movieRepository;

    public MovieAdminService(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    /** 新增并上架影片（UC101）。 */
    @Transactional
    public MovieResponse create(MovieCreateRequest request) {
        Movie movie = new Movie();
        movie.setTitle(request.title());
        movie.setGenre(request.genre());
        movie.setDurationMin(request.durationMin());
        movie.setPosterUrl(request.posterUrl());
        movie.setStatus(MovieStatus.ON_SHOW);
        return toResponse(movieRepository.save(movie));
    }

    /** 编辑影片信息或上下架（UC104，部分更新：仅非 null 字段生效）。 */
    @Transactional
    public MovieResponse update(Long id, MovieUpdateRequest request) {
        Movie movie = movieRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.MOVIE_NOT_FOUND));
        if (request.title() != null) {
            movie.setTitle(request.title());
        }
        if (request.genre() != null) {
            movie.setGenre(request.genre());
        }
        if (request.durationMin() != null) {
            movie.setDurationMin(request.durationMin());
        }
        if (request.posterUrl() != null) {
            movie.setPosterUrl(request.posterUrl());
        }
        if (request.status() != null) {
            movie.setStatus(request.status());
        }
        return toResponse(movie);
    }

    private MovieResponse toResponse(Movie movie) {
        return new MovieResponse(movie.getId(), movie.getTitle(), movie.getGenre(),
                movie.getDurationMin(), movie.getPosterUrl(), movie.getStatus());
    }
}
