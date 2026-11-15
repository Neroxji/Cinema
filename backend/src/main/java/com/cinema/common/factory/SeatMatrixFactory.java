package com.cinema.common.factory;

import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.enums.HallType;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;

import java.util.ArrayList;
import java.util.List;

/**
 * 座位模板工厂（抽象 Creator）：按影厅类型为场次生成初始座位矩阵。
 *
 * <p>// @Pattern: Factory Method(抽象 Creator：公共骨架 createSeats 固定建座流程，
 * <br>具体产品差异由工厂方法 zoneOf/createSeat 交给子类决定), @UseCase: UC102/UC103
 *
 * <p>使用方：DataSeeder（本卡）与 task-02 的创建场次（UC103-4"系统按座位模板为该场次
 * 生成全部空闲座位"）。模板参数（行列数/分区规则）同时用于生成 cinema_hall 影厅记录，
 * 保证 hall.rows × hall.cols、zone_rule 与每个 seat.zone 三者一致。
 */
// @Pattern: Factory Method(抽象Creator), @UseCase: UC102/UC103
public abstract class SeatMatrixFactory {

    /** 本工厂适配的影厅类型。 */
    public abstract HallType hallType();

    /** 模板行数（排数）。 */
    public abstract int rowCount();

    /** 模板列数（每排座位数）。 */
    public abstract int colCount();

    /** 分区规则 JSON（写入 cinema_hall.zone_rule，与 zoneOf 的规则一致）。 */
    public abstract String zoneRule();

    /** 工厂方法角色：由子类决定某位置座位的分区（产品差异点）。 */
    protected abstract SeatZone zoneOf(int rowNum, int colNum);

    /**
     * 按模板为场次生成全部初始座位（UC103-4：全部"空闲"）。
     * 座位编号统一：rowNum 从 1 起、colNum 从 1 起。
     */
    public List<Seat> createSeats(Showing showing) {
        List<Seat> seats = new ArrayList<>(rowCount() * colCount());
        for (int row = 1; row <= rowCount(); row++) {
            for (int col = 1; col <= colCount(); col++) {
                Seat seat = new Seat();
                seat.setShowing(showing);
                seat.setRowNum(row);
                seat.setColNum(col);
                seat.setZone(zoneOf(row, col));
                seat.setStatus(SeatState.FREE);
                seat.setVersion(0L);
                seats.add(seat);
            }
        }
        return seats;
    }
}
