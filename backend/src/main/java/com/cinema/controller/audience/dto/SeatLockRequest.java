package com.cinema.controller.audience.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 选座锁座请求（POST /api/showings/{id}/seats/lock，UC204）。
 */
public record SeatLockRequest(

        /** 所选座位 id 列表（来自座位图；去重升序处理，单笔 ≤4 座——UC204-3）。 */
        @NotEmpty(message = "至少选择一个座位")
        List<@NotNull(message = "座位 id 不能为空") Long> seatIds
) {
}
