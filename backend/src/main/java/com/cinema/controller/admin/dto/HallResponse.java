package com.cinema.controller.admin.dto;

import com.cinema.model.enums.HallType;

/**
 * 影厅响应（POST /api/admin/halls 的 data）。
 */
public record HallResponse(

        /** 影厅 id。 */
        Long id,

        /** 影厅名称。 */
        String name,

        /** 影厅类型：IMAX / STANDARD。 */
        HallType hallType,

        /** 行数（排数，由工厂模板决定）。 */
        Integer rows,

        /** 列数（每排座位数，由工厂模板决定）。 */
        Integer cols,

        /** 分区规则 JSON（由工厂写入，如 {"type":"GOLDEN_ROWS","goldenRows":[2,3,4],...}）。 */
        String zoneRule,

        /** 座位总数 = rows × cols。 */
        Integer seatCount
) {
}
