package com.cinema.controller.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 新增影片请求（POST /api/admin/movies，UC101）。
 *
 * <p>UC101 语义"录入并上架"：创建即 ON_SHOW（上映中），请求不携带状态字段。
 */
public record MovieCreateRequest(

        /** 片名（必填，≤100 字）。 */
        @NotBlank(message = "片名不能为空")
        @Size(max = 100, message = "片名长度不能超过 100")
        String title,

        /** 类型（可选，≤50 字，如 科幻/动作/喜剧）。 */
        @Size(max = 50, message = "类型长度不能超过 50")
        String genre,

        /** 片长分钟数（可选，>0；用于创建场次的时段冲突推算）。 */
        @Positive(message = "片长必须大于 0")
        Integer durationMin,

        /** 海报地址（可选，≤255 字）。 */
        @Size(max = 255, message = "海报地址长度不能超过 255")
        String posterUrl
) {
}
