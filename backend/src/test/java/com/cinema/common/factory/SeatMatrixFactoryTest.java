package com.cinema.common.factory;

import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.enums.HallType;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U5 · 座位模板工厂测试（测试计划 U5，UC102/103）：
 * IMAX 5×10=50 座黄金区标注正确、普通厅 6×8=48 座不分区。
 *
 * <p>纯单元测试：直接 new 工厂与 Showing 裸实例，不起 Spring 容器、不连数据库
 * （复用 task-01 既有 SeatMatrixFactory 三件套实现做行为验证，不修改工厂）。
 */
@DisplayName("U5 SeatMatrixFactory：IMAX 5×10=50 座黄金区标注正确、普通厅 6×8=48 座")
class SeatMatrixFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ImaxSeatFactory imaxSeatFactory = new ImaxSeatFactory();
    private final StandardSeatFactory standardSeatFactory = new StandardSeatFactory();

    @Test
    @DisplayName("IMAX 工厂：createSeats 生成 50 座（5 排×10 列），编号唯一且全部初始空闲")
    void imaxGeneratesFiftyUniqueFreeSeats() {
        Showing showing = new Showing();
        List<Seat> seats = imaxSeatFactory.createSeats(showing);

        assertEquals(50, seats.size(), "IMAX 应生成 5×10=50 座");
        Set<String> positions = new HashSet<>();
        for (Seat seat : seats) {
            positions.add(seat.getRowNum() + "-" + seat.getColNum());
            assertEquals(SeatState.FREE, seat.getStatus(), "UC103-4：初始座位应全部空闲");
            assertEquals(0L, seat.getVersion(), "新座位乐观锁版本号应为 0");
            assertSame(showing, seat.getShowing(), "座位应挂接所属场次");
        }
        assertEquals(50, positions.size(), "座位编号（排,列）不得重复");
        for (int row = 1; row <= 5; row++) {
            for (int col = 1; col <= 10; col++) {
                assertTrue(positions.contains(row + "-" + col),
                        "应存在座位（" + row + "," + col + "）");
            }
        }
    }

    @Test
    @DisplayName("IMAX 黄金区标注：第 2~4 排为 GOLDEN（30 座），第 1/5 排为 NORMAL（20 座）")
    void imaxGoldenZoneRowsCorrect() {
        List<Seat> seats = imaxSeatFactory.createSeats(new Showing());

        long golden = seats.stream().filter(s -> s.getZone() == SeatZone.GOLDEN).count();
        long normal = seats.stream().filter(s -> s.getZone() == SeatZone.NORMAL).count();
        assertEquals(30, golden, "黄金区应为 3 排×10 座=30 座");
        assertEquals(20, normal, "普通区应为 2 排×10 座=20 座");

        for (Seat seat : seats) {
            if (seat.getRowNum() >= ImaxSeatFactory.GOLDEN_ROW_FROM
                    && seat.getRowNum() <= ImaxSeatFactory.GOLDEN_ROW_TO) {
                assertEquals(SeatZone.GOLDEN, seat.getZone(),
                        "第 2~4 排应标注黄金区：" + seat.getRowNum() + "-" + seat.getColNum());
            } else {
                assertEquals(SeatZone.NORMAL, seat.getZone(),
                        "第 1/5 排应标注普通区：" + seat.getRowNum() + "-" + seat.getColNum());
            }
        }
    }

    @Test
    @DisplayName("普通厅工厂：createSeats 生成 48 座（6 排×8 列），全部 NORMAL 不分区")
    void standardGeneratesFortyEightNormalSeats() {
        Showing showing = new Showing();
        List<Seat> seats = standardSeatFactory.createSeats(showing);

        assertEquals(48, seats.size(), "普通厅应生成 6×8=48 座");
        Set<String> positions = new HashSet<>();
        for (Seat seat : seats) {
            positions.add(seat.getRowNum() + "-" + seat.getColNum());
            assertEquals(SeatZone.NORMAL, seat.getZone(), "普通厅座位不应出现黄金区标注");
            assertEquals(SeatState.FREE, seat.getStatus());
        }
        assertEquals(48, positions.size());
        for (int row = 1; row <= 6; row++) {
            for (int col = 1; col <= 8; col++) {
                assertTrue(positions.contains(row + "-" + col),
                        "应存在座位（" + row + "," + col + "）");
            }
        }
    }

    @Test
    @DisplayName("工厂模板元数据：IMAX=IMAX/5/10，普通=STANDARD/6/8")
    void factoryMetadata() {
        assertEquals(HallType.IMAX, imaxSeatFactory.hallType());
        assertEquals(5, imaxSeatFactory.rowCount());
        assertEquals(10, imaxSeatFactory.colCount());

        assertEquals(HallType.STANDARD, standardSeatFactory.hallType());
        assertEquals(6, standardSeatFactory.rowCount());
        assertEquals(8, standardSeatFactory.colCount());
    }

    @Test
    @DisplayName("分区规则 JSON：IMAX 写入 GOLDEN_ROWS[2,3,4]，普通厅 type=NONE")
    void zoneRuleJsonMatchesSeatZones() throws Exception {
        JsonNode imaxRule = objectMapper.readTree(imaxSeatFactory.zoneRule());
        assertEquals("GOLDEN_ROWS", imaxRule.path("type").asText());
        assertEquals(3, imaxRule.path("goldenRows").size(), "黄金区应为 3 排");
        assertEquals(2, imaxRule.path("goldenRows").get(0).asInt());
        assertEquals(4, imaxRule.path("goldenRows").get(2).asInt());

        JsonNode standardRule = objectMapper.readTree(standardSeatFactory.zoneRule());
        assertEquals("NONE", standardRule.path("type").asText());
        assertEquals(0, standardRule.path("goldenRows").size(), "普通厅不应有黄金区行");

        assertNotNull(imaxRule.path("desc").asText());
    }
}
