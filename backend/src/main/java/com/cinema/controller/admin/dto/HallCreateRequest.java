package com.cinema.controller.admin.dto;

import com.cinema.model.enums.HallType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建影厅请求（POST /api/admin/halls，UC102）。
 *
 * <p>行列数与分区规则不在请求中传入：座位模板由工厂按 hallType 固定提供
 * （spec §5 工厂方法——IMAX 5 排×10 座=50 座第 2~4 排黄金区；普通厅 6 排×8 座=48 座不分区），
 * 保证"模板声明"（cinema_hall.rows/cols/zone_rule）与"座位生成"（seat.zone）同源。
 * 创建影厅只落模板，不预生成座位——座位按场次在 UC103-4 由工厂生成。
 */
public record HallCreateRequest(

        /** 影厅名称（必填，≤50 字）。 */
        @NotBlank(message = "影厅名称不能为空")
        @Size(max = 50, message = "影厅名称长度不能超过 50")
        String name,

        /** 影厅类型（必填）：IMAX / STANDARD，决定座位模板工厂。 */
        @NotNull(message = "影厅类型不能为空")
        HallType hallType
) {
}
