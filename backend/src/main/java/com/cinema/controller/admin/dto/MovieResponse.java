package com.cinema.controller.admin.dto;

import com.cinema.model.enums.MovieStatus;

/**
 * 影片响应（POST/PUT /api/admin/movies 的 data）。
 */
public record MovieResponse(

        /** 影片 id。 */
        Long id,

        /** 片名。 */
        String title,

        /** 类型。 */
        String genre,

        /** 片长（分钟）。 */
        Integer durationMin,

        /** 海报地址。 */
        String posterUrl,

        /** 状态：ON_SHOW 上映中 / OFF_SHOW 已下架。 */
        MovieStatus status
) {
}
