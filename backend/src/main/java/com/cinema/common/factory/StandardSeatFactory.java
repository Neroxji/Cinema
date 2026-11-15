package com.cinema.common.factory;

import com.cinema.model.enums.HallType;
import com.cinema.model.enums.SeatZone;
import org.springframework.stereotype.Component;

/**
 * 普通厅座位工厂（具体 Creator）：6 排 × 8 座 = 48 座，不分区（全部普通区）。
 *
 * <p>// @Pattern: Factory Method(具体 Creator), @UseCase: UC102/UC103
 */
// @Pattern: Factory Method(具体Creator), @UseCase: UC102/UC103
@Component
public class StandardSeatFactory extends SeatMatrixFactory {

    /** 普通厅固定模板：6 排。 */
    public static final int ROW_COUNT = 6;

    /** 普通厅固定模板：每排 8 座。 */
    public static final int COL_COUNT = 8;

    @Override
    public HallType hallType() {
        return HallType.STANDARD;
    }

    @Override
    public int rowCount() {
        return ROW_COUNT;
    }

    @Override
    public int colCount() {
        return COL_COUNT;
    }

    @Override
    public String zoneRule() {
        return "{\"type\":\"NONE\",\"goldenRows\":[],\"desc\":\"普通厅不分区\"}";
    }

    @Override
    protected SeatZone zoneOf(int rowNum, int colNum) {
        return SeatZone.NORMAL;
    }
}
