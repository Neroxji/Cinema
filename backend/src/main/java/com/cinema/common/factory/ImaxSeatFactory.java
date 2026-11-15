package com.cinema.common.factory;

import com.cinema.model.enums.HallType;
import com.cinema.model.enums.SeatZone;
import org.springframework.stereotype.Component;

/**
 * IMAX 厅座位工厂（具体 Creator）：5 排 × 10 座 = 50 座，第 2~4 排为黄金区。
 *
 * <p>// @Pattern: Factory Method(具体 Creator), @UseCase: UC102/UC103
 *
 * <p>黄金区规则同时写入 cinema_hall.zone_rule（JSON）与每个 seat.zone，
 * 分区计价（ZONE_PRICING）按该标注取 goldenPrice/normalPrice（UC105）。
 */
// @Pattern: Factory Method(具体Creator), @UseCase: UC102/UC103
@Component
public class ImaxSeatFactory extends SeatMatrixFactory {

    /** IMAX 固定模板：5 排（spec §7.6：IMAX 10×5=50 座，10 列 × 5 排）。 */
    public static final int ROW_COUNT = 5;

    /** IMAX 固定模板：每排 10 座。 */
    public static final int COL_COUNT = 10;

    /** 黄金区起始排（居中三排）。 */
    public static final int GOLDEN_ROW_FROM = 2;

    /** 黄金区结束排。 */
    public static final int GOLDEN_ROW_TO = 4;

    @Override
    public HallType hallType() {
        return HallType.IMAX;
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
        StringBuilder goldenRows = new StringBuilder("[");
        for (int row = GOLDEN_ROW_FROM; row <= GOLDEN_ROW_TO; row++) {
            if (row > GOLDEN_ROW_FROM) {
                goldenRows.append(',');
            }
            goldenRows.append(row);
        }
        goldenRows.append(']');
        return "{\"type\":\"GOLDEN_ROWS\",\"goldenRows\":" + goldenRows
                + ",\"desc\":\"IMAX 黄金区：第" + GOLDEN_ROW_FROM + "~" + GOLDEN_ROW_TO + "排\"}";
    }

    @Override
    protected SeatZone zoneOf(int rowNum, int colNum) {
        return rowNum >= GOLDEN_ROW_FROM && rowNum <= GOLDEN_ROW_TO ? SeatZone.GOLDEN : SeatZone.NORMAL;
    }
}
