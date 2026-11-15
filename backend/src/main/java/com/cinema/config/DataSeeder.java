package com.cinema.config;

import com.cinema.common.PasswordHasher;
import com.cinema.common.factory.ImaxSeatFactory;
import com.cinema.common.factory.SeatMatrixFactory;
import com.cinema.common.factory.StandardSeatFactory;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 种子数据播种器（ApplicationRunner，幂等：user 表已有数据则整体跳过）。
 *
 * <p>按 spec §7.6 写入演示数据：
 * <ul>
 *   <li>3 演示账号（观众/排片员/检票员各 1，密码哈希存储）</li>
 *   <li>3 部影片（全部上映中，信息各异）</li>
 *   <li>3 影厅（1 个 IMAX 5×10=50 座 + 2 个普通 6×8=48 座），座位模板与分区规则由工厂生成</li>
 *   <li>6 场次（开映时间均在未来；票价策略覆盖三档预设各一，price_params 合法）</li>
 *   <li>其中 1 个满座场：全部座位"已售" + 自洽的已出票订单与明细
 *       （快照价与该场次策略一致，每张已售座位有且仅有一条有效明细）</li>
 * </ul>
 *
 * <p>快照价计算口径（calcSnapshotPrice）与 task-02 的 PriceStrategy 策略实现保持一致；
 * 后续若抽公共计价器，应将本私有方法迁出，DataSeeder 仅保留编排。
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final MovieRepository movieRepository;
    private final CinemaHallRepository cinemaHallRepository;
    private final ShowingRepository showingRepository;
    private final SeatRepository seatRepository;
    private final TicketOrderRepository ticketOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ImaxSeatFactory imaxSeatFactory;
    private final StandardSeatFactory standardSeatFactory;

    public DataSeeder(ObjectMapper objectMapper,
                      UserRepository userRepository,
                      MovieRepository movieRepository,
                      CinemaHallRepository cinemaHallRepository,
                      ShowingRepository showingRepository,
                      SeatRepository seatRepository,
                      TicketOrderRepository ticketOrderRepository,
                      OrderItemRepository orderItemRepository,
                      ImaxSeatFactory imaxSeatFactory,
                      StandardSeatFactory standardSeatFactory) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.movieRepository = movieRepository;
        this.cinemaHallRepository = cinemaHallRepository;
        this.showingRepository = showingRepository;
        this.seatRepository = seatRepository;
        this.ticketOrderRepository = ticketOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.imaxSeatFactory = imaxSeatFactory;
        this.standardSeatFactory = standardSeatFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 幂等保护：已有账号数据则视为已播种，整体跳过
        if (userRepository.count() > 0) {
            log.info("[DataSeeder] 检测到 user 表已有数据，跳过种子数据播种（幂等保护）");
            return;
        }
        LocalDateTime base = LocalDateTime.now();

        // ---------- 1. 演示账号 ×3（密码哈希存储） ----------
        User audience = saveUser("audience", "audience123", UserRole.AUDIENCE, "13800000001");
        saveUser("scheduler", "scheduler123", UserRole.SCHEDULER, "13800000002");
        saveUser("checker", "checker123", UserRole.CHECKER, "13800000003");

        // ---------- 2. 影片 ×3（上映中，信息各异） ----------
        Movie movieEarth = saveMovie("流浪地球3", "科幻", 140, "https://img.example.com/poster/diqiu3.jpg");
        Movie movieZhou = saveMovie("周处除三害", "动作", 134, "https://img.example.com/poster/zhoucu.jpg");
        Movie movieFeichi = saveMovie("飞驰人生2", "喜剧", 121, "https://img.example.com/poster/feichi2.jpg");

        // ---------- 3. 影厅 ×3（1 IMAX 5×10 + 2 普通 6×8），模板参数与分区规则由工厂提供（UC102） ----------
        CinemaHall imaxHall = saveHall("IMAX厅", imaxSeatFactory);
        CinemaHall hall1 = saveHall("1号厅", standardSeatFactory);
        CinemaHall hall2 = saveHall("2号厅", standardSeatFactory);

        // ---------- 4. 场次 ×6（开映均在未来；票价策略三档预设各至少一场，UC105） ----------
        // s1 为满座场（候补递补演示用，UC304/305）：分区计价 → 快照价按黄金区/普通区两档
        Showing s1 = saveShowingWithSeats(movieEarth, imaxHall, base.plusHours(26), PriceType.ZONE_PRICING,
                "{\"goldenPrice\":45.00,\"normalPrice\":35.00}");
        Showing s2 = saveShowingWithSeats(movieZhou, hall1, base.plusHours(28), PriceType.MEMBER_DISCOUNT,
                "{\"basePrice\":40.00,\"discount\":0.8}");
        Showing s3 = saveShowingWithSeats(movieFeichi, hall2, base.plusHours(30), PriceType.ORIGINAL,
                "{\"basePrice\":38.00}");
        Showing s4 = saveShowingWithSeats(movieEarth, hall1, base.plusHours(50), PriceType.ORIGINAL,
                "{\"basePrice\":42.00}");
        Showing s5 = saveShowingWithSeats(movieZhou, imaxHall, base.plusHours(52), PriceType.ZONE_PRICING,
                "{\"goldenPrice\":48.00,\"normalPrice\":38.00}");
        Showing s6 = saveShowingWithSeats(movieFeichi, hall2, base.plusHours(54), PriceType.MEMBER_DISCOUNT,
                "{\"basePrice\":36.00,\"discount\":0.8}");

        // ---------- 5. 满座场：s1 全部座位已售 + 自洽已出票订单 ----------
        seedFullHouseShowing(s1, audience);

        // ---------- 6. 播种统计日志 ----------
        log.info("[DataSeeder] 种子数据播种完成：账号 3（观众/排片员/检票员各 1）、影片 3（全部上映中）、"
                        + "影厅 3（IMAX 5×10=50 座 ×1 + 普通 6×8=48 座 ×2）、场次 6（三档票价预设各至少一场）、"
                        + "座位 {} 个（空闲 {} / 锁定 {} / 已售 {}）、已出票订单 {} 笔（满座场为候补演示预留）",
                seatRepository.count(),
                seatRepository.countByStatus(SeatState.FREE),
                seatRepository.countByStatus(SeatState.LOCKED),
                seatRepository.countByStatus(SeatState.SOLD),
                ticketOrderRepository.count());
    }

    // ---------------- 内部方法 ----------------

    private User saveUser(String username, String rawPassword, UserRole role, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(PasswordHasher.encode(rawPassword));
        user.setRole(role);
        user.setPhone(phone);
        return userRepository.save(user);
    }

    private Movie saveMovie(String title, String genre, int durationMin, String posterUrl) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setGenre(genre);
        movie.setDurationMin(durationMin);
        movie.setPosterUrl(posterUrl);
        movie.setStatus(MovieStatus.ON_SHOW);
        return movieRepository.save(movie);
    }

    /** 影厅行列数与分区规则直接取自工厂，保证"模板声明"与"座位生成"同源（UC102）。 */
    private CinemaHall saveHall(String name, SeatMatrixFactory factory) {
        CinemaHall hall = new CinemaHall();
        hall.setName(name);
        hall.setHallType(factory.hallType());
        hall.setRows(factory.rowCount());
        hall.setCols(factory.colCount());
        hall.setZoneRule(factory.zoneRule());
        return cinemaHallRepository.save(hall);
    }

    /** 创建场次并按影厅类型选择工厂生成全部空闲座位（UC103-4）。 */
    private Showing saveShowingWithSeats(Movie movie, CinemaHall hall, LocalDateTime startTime,
                                         PriceType priceType, String priceParams) {
        Showing showing = new Showing();
        showing.setMovie(movie);
        showing.setHall(hall);
        showing.setStartTime(startTime);
        showing.setStatus(ShowingStatus.PUBLISHED);
        showing.setPriceType(priceType);
        showing.setPriceParams(priceParams);
        showing = showingRepository.save(showing);

        SeatMatrixFactory factory = hall.getHallType() == HallType.IMAX ? imaxSeatFactory : standardSeatFactory;
        seatRepository.saveAll(factory.createSeats(showing));
        return showing;
    }

    /**
     * 满座场（UC304/305 演示前置）：全部座位置"已售"，并生成一笔自洽的"已出票"订单。
     * 自洽性：每张已售座位有且仅有一条有效明细（valid_flag=1，满足唯一索引
     * uk_order_item_seat_valid）；快照价与该场次分区计价策略一致；订单总额=明细之和。
     */
    private void seedFullHouseShowing(Showing showing, User buyer) {
        List<Seat> seats = seatRepository.findByShowingId(showing.getId());

        TicketOrder order = new TicketOrder();
        order.setOrderNo("SEED" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
        order.setUser(buyer);
        order.setShowing(showing);
        order.setStatus(OrderState.ISSUED);
        order.setPayTime(LocalDateTime.now().minusMinutes(30));

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> items = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            seat.setStatus(SeatState.SOLD);
            BigDecimal price = calcSnapshotPrice(seat, showing);
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setSeat(seat);
            item.setPriceSnapshot(price);
            item.setValidFlag(1L);
            items.add(item);
            total = total.add(price);
        }
        seatRepository.saveAll(seats);
        order.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        ticketOrderRepository.save(order);
        orderItemRepository.saveAll(items);

        log.info("[DataSeeder] 满座场播种完成：showingId={}，座位 {} 张全部已售，订单 {}（金额 {}）",
                showing.getId(), seats.size(), order.getOrderNo(), order.getTotalAmount());
    }

    /**
     * 快照价计算（与 task-02 PriceStrategy 策略实现保持同一口径，UC105 三档预设）：
     * ORIGINAL=basePrice；MEMBER_DISCOUNT=basePrice×discount；ZONE_PRICING 按座位分区取价。
     */
    private BigDecimal calcSnapshotPrice(Seat seat, Showing showing) {
        try {
            JsonNode params = objectMapper.readTree(showing.getPriceParams());
            BigDecimal price;
            switch (showing.getPriceType()) {
                case ORIGINAL -> price = BigDecimal.valueOf(params.get("basePrice").asDouble());
                case MEMBER_DISCOUNT -> price = BigDecimal.valueOf(params.get("basePrice").asDouble())
                        .multiply(BigDecimal.valueOf(params.get("discount").asDouble()));
                case ZONE_PRICING -> price = seat.getZone() == SeatZone.GOLDEN
                        ? BigDecimal.valueOf(params.get("goldenPrice").asDouble())
                        : BigDecimal.valueOf(params.get("normalPrice").asDouble());
                default -> throw new IllegalStateException("未知票价策略类型：" + showing.getPriceType());
            }
            return price.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new IllegalStateException("种子场次 price_params 非法：" + showing.getPriceParams(), e);
        }
    }
}
