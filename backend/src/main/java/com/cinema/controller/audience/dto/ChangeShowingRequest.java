package com.cinema.controller.audience.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 改签请求体（POST /api/orders/{id}/change，UC303）。
 */
public record ChangeShowingRequest(

        /** 改签目标场次 id（须为同影片其他已发布、未开映且余座充足的场次）。 */
        @NotNull(message = "改签目标场次不可为空")
        Long targetShowingId
) {
}
