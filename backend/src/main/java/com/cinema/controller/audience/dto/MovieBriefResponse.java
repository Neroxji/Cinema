package com.cinema.controller.audience.dto;

import com.cinema.model.Movie;

/**
 * 上映中影片卡片（GET /api/movies 的 data 元素，UC202）。
 */
public record MovieBriefResponse(

        /** 影片 id。 */
        Long id,

        /** 片名。 */
        String title,

        /** 类型。 */
        String genre,

        /** 片长（分钟）。 */
        Integer durationMin,

        /** 海报地址。 */
        String posterUrl
) {
    public static MovieBriefResponse from(Movie movie) {
        return new MovieBriefResponse(movie.getId(), movie.getTitle(), movie.getGenre(),
                movie.getDurationMin(), movie.getPosterUrl());
    }
}
