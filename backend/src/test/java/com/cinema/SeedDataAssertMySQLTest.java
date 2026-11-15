package com.cinema;

import com.cinema.common.PasswordHasher;
import com.cinema.config.DataSeeder;
import com.cinema.model.CinemaHall;
import com.cinema.model.Movie;
import com.cinema.model.OrderItem;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.User;
import com.cinema.model.enums.HallType;
import com.cinema.model.enums.MovieStatus;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;
import com.cinema.model.enums.ShowingStatus;
import com.cinema.model.enums.UserRole;
import com.cinema.repository.CinemaHallRepository;
import com.cinema.repository.MovieRepository;
import com.cinema.repository.OrderItemRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.TicketOrderRepository;
import com.cinema.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子数据断言测试 · MySQL 档复跑（task-01C 交付物 3）。
 *
 * <p>与 {@link SeedDataAssertTest} 同一套 8 用例，数据源切换为真 MySQL——
 * task-01C 常驻容器 cinema-mysql（MySQL 8，host 3306，库 cinema，数据卷持久化），
 * 由 mysql-test profile（application-mysql-test.yml）承载。
 *
 * <p>复跑前置：容器运行中（docker start cinema-mysql）。复跑命令：
 * <pre>mvn test -Dtest=SeedDataAssertMySQLTest -Dcinema.mysql.tests=true</pre>
 * 套件级开关 {@code -Dcinema.mysql.tests=true}：缺省时本类跳过（默认
 * {@code mvn test} 只跑 H2 内存档，无 Docker 环境不受影响），显式开启才对真 MySQL 执行。
 *
 * <p>安全性：schema.sql 全表 IF NOT EXISTS 幂等 + DataSeeder 幂等 + 本类
 * {@code @Transactional} 回滚——对持久库重复复跑安全、不污染种子数据。
 */
@SpringBootTest
@ActiveProfiles("mysql-test")
@Transactional
@EnabledIfSystemProperty(named = "cinema.mysql.tests", matches = "true",
        disabledReason = "对真 MySQL 复跑需显式开启 -Dcinema.mysql.tests=true 且 cinema-mysql 容器运行中")
@DisplayName("种子数据断言（schema.sql + DataSeeder + 容器 MySQL 8 mysql-test 档）")
class SeedDataAssertMySQLTest {

    /** 满座场（IMAX 分区计价）黄金区快照价。 */
    private static final BigDecimal IMAX_GOLDEN_PRICE = new BigDecimal("45.00");

    /** 满座场（IMAX 分区计价）普通区快照价。 */
    private static final BigDecimal IMAX_NORMAL_PRICE = new BigDecimal("35.00");

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MovieRepository movieRepository;
    @Autowired
    private CinemaHallRepository cinemaHallRepository;
    @Autowired
    private ShowingRepository showingRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private TicketOrderRepository ticketOrderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private DataSeeder dataSeeder;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("演示账号=3，三类角色齐全，密码哈希可校验")
    void seedDemoAccounts() {
        List<User> users = userRepository.findAll();
        assertEquals(3, users.size());

        Set<UserRole> roles = users.stream().map(User::getRole).collect(Collectors.toSet());
        assertEquals(Set.of(UserRole.AUDIENCE, UserRole.SCHEDULER, UserRole.CHECKER), roles,
                "观众/排片员/检票员三类角色应各 1 个");

        User audience = userRepository.findByUsername("audience").orElseThrow();
        assertTrue(PasswordHasher.verify("audience123", audience.getPasswordHash()),
                "观众密码校验应通过");
        assertFalse(PasswordHasher.verify("wrong-password", audience.getPasswordHash()),
                "错误密码应校验失败");
        assertTrue(PasswordHasher.verify("scheduler123",
                userRepository.findByUsername("scheduler").orElseThrow().getPasswordHash()));
        assertTrue(PasswordHasher.verify("checker123",
                userRepository.findByUsername("checker").orElseThrow().getPasswordHash()));
    }

    @Test
    @DisplayName("影片=3，全部上映中且信息各异")
    void seedMovies() {
        List<Movie> movies = movieRepository.findAll();
        assertEquals(3, movies.size());
        assertTrue(movies.stream().allMatch(m -> m.getStatus() == MovieStatus.ON_SHOW),
                "三部分影片均应为上映中");
        assertEquals(3, movies.stream().map(Movie::getTitle).distinct().count(), "片名应互不相同");
        assertTrue(movies.stream().allMatch(m -> m.getDurationMin() != null && m.getDurationMin() > 0));
    }

    @Test
    @DisplayName("影厅=3：IMAX 5×10=50 座，普通厅各 6×8=48 座，分区规则非空")
    void seedHalls() {
        List<CinemaHall> halls = cinemaHallRepository.findAll();
        assertEquals(3, halls.size());

        CinemaHall imax = halls.stream()
                .filter(h -> h.getHallType() == HallType.IMAX)
                .findFirst().orElseThrow();
        assertEquals(5, imax.getRows());
        assertEquals(10, imax.getCols());
        assertTrue(imax.getZoneRule().contains("GOLDEN_ROWS"), "IMAX 影厅应写入黄金区分区规则");

        List<CinemaHall> standards = halls.stream()
                .filter(h -> h.getHallType() == HallType.STANDARD).toList();
        assertEquals(2, standards.size());
        for (CinemaHall hall : standards) {
            assertEquals(6, hall.getRows());
            assertEquals(8, hall.getCols());
        }
    }

    @Test
    @DisplayName("场次=6，开映时间均在未来且未开映，票价策略覆盖三档预设")
    void seedShowings() {
        List<Showing> showings = showingRepository.findAll();
        assertEquals(6, showings.size());

        LocalDateTime now = LocalDateTime.now();
        assertTrue(showings.stream().allMatch(s -> s.getStartTime().isAfter(now)),
                "全部场次开映时间应在未来（未开映）");
        assertTrue(showings.stream().allMatch(s -> s.getStatus() == ShowingStatus.PUBLISHED),
                "全部场次应为已发布状态");

        Set<PriceType> priceTypes = showings.stream().map(Showing::getPriceType).collect(Collectors.toSet());
        assertEquals(Set.of(PriceType.ORIGINAL, PriceType.MEMBER_DISCOUNT, PriceType.ZONE_PRICING),
                priceTypes, "票价策略应覆盖全场原价/会员日 8 折/分区计价三档预设各至少一场");

        // price_params 必须是合法 JSON（三档参数约定见 PriceType 注释）
        for (Showing showing : showings) {
            assertDoesNotThrow(() -> objectMapper.readTree(showing.getPriceParams()),
                    "price_params 应为合法 JSON：" + showing.getPriceParams());
        }
    }

    @Test
    @DisplayName("满座场：全部座位已售、有效明细数=座位数、快照价与分区计价策略一致、订单自洽")
    void seedFullHouseShowing() {
        // 恰好 1 个满座场（全部座位已售）
        List<Showing> fullOnes = showingRepository.findAll().stream()
                .filter(s -> seatRepository.findByShowingId(s.getId()).stream()
                        .allMatch(seat -> seat.getStatus() == SeatState.SOLD))
                .toList();
        assertEquals(1, fullOnes.size(), "应有且仅有一个满座场");

        Showing full = fullOnes.get(0);
        List<Seat> seats = seatRepository.findByShowingId(full.getId());
        assertEquals(50, seats.size(), "满座场应为 IMAX 厅 50 座");
        assertTrue(seats.stream().allMatch(s -> s.getVersion() != null && s.getVersion() >= 1),
                "已售座位乐观锁版本号应在置售更新时 +1");

        // 一笔自洽的已出票订单
        assertEquals(1, ticketOrderRepository.count(), "种子数据应恰有 1 笔订单");
        TicketOrder order = ticketOrderRepository.findAll().get(0);
        assertEquals(OrderState.ISSUED, order.getStatus(), "满座场订单应为已出票");
        assertEquals(full.getId(), order.getShowing().getId());
        assertEquals("audience", order.getUser().getUsername(), "满座场订单应属于演示观众账号");
        assertNotNull(order.getPayTime(), "已出票订单应有支付时间");
        assertNull(order.getCheckinTime(), "未检票，核销时间应为空");
        assertNull(order.getChangeFromShowingId(), "非改签单，改签来源应为空");
        assertNotNull(order.getOrderNo());

        // 取票码（spec §1 / 测试计划 U8 / PRD UC307）：已出票订单应已有取票码，且为 8 位数字
        assertNotNull(order.getPickupCode(), "已出票订单应已有取票码");
        assertTrue(order.getPickupCode().matches("^\\d{8}$"),
                "取票码应为 8 位数字，实际：" + order.getPickupCode());
        assertEquals(DataSeeder.SEED_PICKUP_CODE, order.getPickupCode(),
                "种子取票码应为 DataSeeder 写死常量（便于验收核对）");

        // 有效明细：每张已售座位有且仅有一条 valid_flag=1 的明细
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        assertEquals(50, items.size(), "有效明细数应等于座位数");
        assertTrue(items.stream().allMatch(i -> Long.valueOf(1L).equals(i.getValidFlag())),
                "种子明细均应为有效明细（valid_flag=1）");

        // 快照价与该场次分区计价策略一致，订单总额=明细之和
        Map<Long, OrderItem> bySeat = new HashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderItem item : items) {
            Seat seat = item.getSeat();
            assertNull(bySeat.put(seat.getId(), item), "同一座位不允许出现第二条有效明细");
            BigDecimal expected = seat.getZone() == SeatZone.GOLDEN ? IMAX_GOLDEN_PRICE : IMAX_NORMAL_PRICE;
            assertEquals(0, expected.compareTo(item.getPriceSnapshot()),
                    "快照价应与分区计价策略一致（黄金区 45.00 / 普通区 35.00）");
            sum = sum.add(item.getPriceSnapshot());
        }
        assertEquals(0, sum.compareTo(order.getTotalAmount()), "订单总额应等于有效明细快照价之和");
    }

    @Test
    @DisplayName("其余场次座位全空闲：总座位 292，空闲 242 / 已售 50 / 锁定 0")
    void seedOtherShowingsAllFree() {
        // 2 个 IMAX 场次 ×50 座 + 4 个普通场次 ×48 座 = 292
        assertEquals(292, seatRepository.count());
        assertEquals(50, seatRepository.countByStatus(SeatState.SOLD), "仅满座场 50 座已售");
        assertEquals(0, seatRepository.countByStatus(SeatState.LOCKED), "无锁定座位");
        assertEquals(242, seatRepository.countByStatus(SeatState.FREE), "其余座位应全空闲");

        List<Showing> notFullShowings = showingRepository.findAll().stream()
                .filter(s -> seatRepository.findByShowingId(s.getId()).stream()
                        .anyMatch(seat -> seat.getStatus() != SeatState.SOLD))
                .toList();
        assertEquals(5, notFullShowings.size());
        for (Showing showing : notFullShowings) {
            assertTrue(seatRepository.findByShowingId(showing.getId()).stream()
                            .allMatch(seat -> seat.getStatus() == SeatState.FREE),
                    "非满座场次座位应全部空闲：showingId=" + showing.getId());
        }
    }

    @Test
    @DisplayName("IMAX 黄金区标注存在：IMAX 场次黄金区 30 座/普通区 20 座，普通厅无黄金区")
    void imaxGoldenZoneMarked() {
        List<Showing> imaxShowings = showingRepository.findAll().stream()
                .filter(s -> s.getHall().getHallType() == HallType.IMAX).toList();
        assertEquals(2, imaxShowings.size());
        for (Showing showing : imaxShowings) {
            List<Seat> seats = seatRepository.findByShowingId(showing.getId());
            assertEquals(30, seats.stream().filter(s -> s.getZone() == SeatZone.GOLDEN).count(),
                    "IMAX 第 2~4 排应为黄金区（3 排 × 10 座）");
            assertEquals(20, seats.stream().filter(s -> s.getZone() == SeatZone.NORMAL).count());
        }

        List<Showing> standardShowings = showingRepository.findAll().stream()
                .filter(s -> s.getHall().getHallType() == HallType.STANDARD).toList();
        assertEquals(4, standardShowings.size());
        assertTrue(standardShowings.stream().allMatch(s ->
                        seatRepository.findByShowingId(s.getId()).stream()
                                .noneMatch(seat -> seat.getZone() == SeatZone.GOLDEN)),
                "普通厅座位不应出现黄金区标注");
    }

    @Test
    @DisplayName("DataSeeder 幂等：重复执行 run 不产生重复数据")
    void seederIsIdempotent() {
        dataSeeder.run(new DefaultApplicationArguments(new String[0]));
        assertEquals(3, userRepository.count(), "重复播种后账号仍应为 3");
        assertEquals(3, movieRepository.count());
        assertEquals(3, cinemaHallRepository.count());
        assertEquals(6, showingRepository.count(), "重复播种后场次仍应为 6");
        assertEquals(292, seatRepository.count());
        assertEquals(1, ticketOrderRepository.count(), "重复播种后订单仍应为 1");
    }
}
