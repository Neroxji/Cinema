package com.cinema.controller.admin.dto;

import com.cinema.model.enums.MovieStatus;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 编辑影片请求（PUT /api/admin/movies/{id}，UC104 编辑影片信息或上下架）。
 *
 * <p>部分更新语义：仅非 null 字段被更新，null 字段保持原值。
 * 状态取值 ON_SHOW（上架）/ OFF_SHOW（下架）；下架不级联取消既有场次
 * （UC103-5a 已在下一次创建场次时拦截）。
 */
public record MovieUpdateRequest(

        /** 片名（可选，≤100 字）。 */
        @Size(max = 100, message = "片名长度不能超过 100")
        String title,

        /** 类型（可选，≤50 字）。 */
        @Size(max = 50, message = "类型长度不能超过 50")
        String genre,

        /** 片长分钟数（可选，>0）。 */
        @Positive(message = "片长必须大于 0")
        Integer durationMin,

        /** 海报地址（可选，≤255 字）。 */
        @Size(max = 255, message = "海报地址长度不能超过 255")
        String posterUrl,

        /** 状态（可选）：ON_SHOW 上映中 / OFF_SHOW 已下架。 */
        MovieStatus status
) {
}
