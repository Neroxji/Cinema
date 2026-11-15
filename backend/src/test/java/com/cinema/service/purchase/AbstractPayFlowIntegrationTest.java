package com.cinema.service.purchase;

import com.cinema.config.BookingConfig;
import com.cinema.model.Movie;
import com.cinema.model.OrderItem;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.User;
import com.cinema.model.enums.HallType;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;
import com.cinema.model.enums.ShowingStatus;
import com.cinema.repository.CinemaHallRepository;
import com.cinema.repository.MovieRepository;
import com.cinema.repository.OrderItemRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.TicketOrderRepository;
import com.cinema.repository.UserRepository;
import com.cinema.common.event.DomainEventPublisher;
import com.cinema.service.admin.strategy.PriceStrategy;
import com.cinema.service.admin.strategy.PriceStrategyResolver;
import com.cinema.service.purchase.pay.MockPayGateway;
import com.cinema.service.purchase.pay.OnlinePayFlow;
import com.cinema.service.purchase.pay.GatewayResult;
import com.cinema.service.purchase.pay.PayCommand;
import com.cinema.service.purchase.pay.PayContext;
import com.cinema.service.purchase.pay.PayFlowService;
import com.cinema.service.purchase.pay.PayOutcome;
import com.cinema.task.LockExpireReleaseTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I3~I7 · 支付全链与超时释放集成测试用例主体（测试计划 §3 I3~I7，P0 护栏）。
 *
 * <p>抽象基类只承载用例，profile 由子类声明（机制照搬 I1/I2）：
 * <ul>
 *   <li>{@link PayFlowServiceIntegrationTest}：H2 内存库 test 档（默认 {@code mvn test} 即跑）</li>
 *   <li>{@link PayFlowServiceMySQLIntegrationTest}：容器 MySQL 8 mysql-test 档
 *       （{@code mvn test -Dcinema.mysql.tests=true} 显式复跑）</li>
 * </ul>
 *
 * <p>隔离方式与 I1/I2 一致：类级 {@code @Transactional} 回滚（I6 特例：方法级
 * NOT_SUPPORTED——作废补记/崩溃回滚需真实提交边界，夹具用专用场次并在 finally 清理）。
 */
@SpringBootTest
@Transactional
@DisplayName("I3~I7 · 支付全链（模板方法 UC205）与超时释放（UC206），用例主体")
abstract class AbstractPayFlowIntegrationTest {

    @Autowired
    protected TicketBookingService ticketBookingService;
    @Autowired
    protected PayFlowService payFlowService;
    @Autowired
    protected LockExpireReleaseTask releaseTask;
    @Autowired
    protected BookingConfig bookingConfig;
    @Autowired
    protected TicketOrderRepository ticketOrderRepository;
    @Autowired
    protected OrderItemRepository orderItemRepository;
    @Autowired
    protected SeatRepository seatRepository;
    @Autowired
    protected ShowingRepository showingRepository;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected MovieRepository movieRepository;
    @Autowired
    protected CinemaHallRepository hallRepository;
    @Autowired
    protected EntityManager entityManager;
    @Autowired
    protected PlatformTransactionManager transactionManager;
    @Autowired
    protected OnlinePayFlow onlinePayFlow;
    @Autowired
    protected DomainEventPublisher eventPublisher;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected PriceStrategyResolver priceStrategyResolver;

    // ---------------- 公共夹具 ----------------

    /** 演示观众（下单人）。 */
    protected User audience() {
        return userRepository.findByUsername("audience").orElseThrow();
    }

    /** 取一个全部座位空闲的场次（种子 5 个非满座场次可用）。 */
    protected Showing freeShowing() {
        return showingRepository.findAll().stream()
                .filter(s -> seatRepository.findByShowingId(s.getId()).stream()
                        .allMatch(seat -> seat.getStatus() == SeatState.FREE))
                .findFirst().orElseThrow();
    }

    /** 取该场次前 n 个空闲座位（按 seat.id 升序）。 */
    protected List<Seat> freeSeats(Long showingId, int n) {
        List<Seat> seats = seatRepository.findByShowingId(showingId).stream()
                .filter(s -> s.getStatus() == SeatState.FREE)
                .sorted(Comparator.comparing(Seat::getId))
                .limit(n)
                .toList();
        assertEquals(n, seats.size(), "夹具：该场次空闲座位应足够");
        return seats;
    }

    protected long noticeCount(Long userId, String type) {
        Long count = entityManager.createQuery(
                        "SELECT COUNT(n) FROM Notification n WHERE n.user.id = :uid AND n.type = :type",
                        Long.class)
                .setParameter("uid", userId)
                .setParameter("type", type)
                .getSingleResult();
        return count == null ? 0 : count;
    }

    /** REQUIRES_NEW 事务模板（I6 真实提交边界用）。 */
    protected TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    // =====================================================================
    // I3 · 支付成功全链：ISSUED + SOLD + 明细快照正确 + 取票码 + 通知真实落库
    // =====================================================================

    @Test
    @DisplayName("I3 支付成功全链：订单已出票+取票码、座位已售、快照价=策略计价、通知落库")
    void i3_paySuccessFullChain() throws Exception {
        Showing showing = freeShowing();
        List<Seat> picked = freeSeats(showing.getId(), 2);
        long versionBefore = picked.get(0).getVersion();

        // 锁座下单（编排服务：SeatLockService 条件更新 + 创建 PENDING_PAYMENT 订单）
        TicketBookingService.LockOrderResult lock = ticketBookingService.lockAndCreateOrder(
                showing.getId(), audience().getId(), List.of(picked.get(0).getId(), picked.get(1).getId()));

        TicketOrder pending = ticketOrderRepository.findById(lock.orderId()).orElseThrow();
        assertEquals(OrderState.PENDING_PAYMENT, pending.getStatus(), "锁座后订单应为待支付");
        assertEquals(0, lock.estimatedAmount().compareTo(pending.getTotalAmount()),
                "锁座预估金额应写入 total_amount");
        assertTrue(pending.getOrderNo().startsWith("B") && pending.getOrderNo().length() == 22,
                "订单号格式 B+17 位时间戳+4 位随机（22 字符），实际：" + pending.getOrderNo());
        assertNull(pending.getPickupCode(), "待支付订单尚无取票码");

        // 支付（payFailRate 默认 0.0=必成功）
        PayOutcome outcome = payFlowService.pay(lock.orderId(), audience().getId());
        assertEquals(PayOutcome.PayOutcomeType.SUCCESS, outcome.type(), "支付应成功：" + outcome.message());

        // 订单：已出票 + 支付时间 + 8 位取票码
        TicketOrder order = ticketOrderRepository.findById(lock.orderId()).orElseThrow();
        assertEquals(OrderState.ISSUED, order.getStatus(), "支付成功后订单应为已出票");
        assertNotNull(order.getPayTime(), "应记录支付时间");
        assertNotNull(order.getPickupCode(), "出票应生成取票码");
        assertTrue(order.getPickupCode().matches("^\\d{8}$"),
                "取票码应为 8 位数字，实际：" + order.getPickupCode());

        // 座位：SOLD + 锁定字段清空 + version 再 +1（锁定一次 + 售出一次）
        for (Seat picked1 : picked) {
            Seat stored = seatRepository.findById(picked1.getId()).orElseThrow();
            assertEquals(SeatState.SOLD, stored.getStatus(), "座位应为已售");
            assertNull(stored.getLockUserId(), "售出后锁定人应清空");
            assertNull(stored.getLockExpireAt(), "售出后锁定截止时间应清空（终止倒计时）");
            assertEquals(versionBefore + 2, stored.getVersion(), "条件更新锁定与售出各 version+1");
        }

        // order_item：每座一条有效明细，快照价 = PriceStrategyResolver 按座位 zone 计价（复用不重写）
        List<OrderItem> items = orderItemRepository.findByOrderId(order.getId());
        assertEquals(2, items.size(), "每张票一条明细");
        JsonNode params = objectMapper.readTree(showing.getPriceParams());
        PriceStrategy strategy = priceStrategyResolver.of(showing.getPriceType());
        BigDecimal sum = BigDecimal.ZERO;
        for (OrderItem item : items) {
            assertEquals(1L, item.getValidFlag(), "明细应为有效（valid_flag=1）");
            BigDecimal expected = strategy.calculate(item.getSeat().getZone(), params);
            assertEquals(0, expected.compareTo(item.getPriceSnapshot()),
                    "快照价应等于策略计价（zone=" + item.getSeat().getZone() + "）");
            sum = sum.add(item.getPriceSnapshot());
        }
        assertEquals(0, sum.compareTo(order.getTotalAmount()), "total_amount 应等于明细快照合计");

        // 通知落库（出票事件真实广播 → NoticeSubscriber）
        assertEquals(1, noticeCount(audience().getId(), "TICKET_ISSUED"),
                "出票事件应广播并落一条 TICKET_ISSUED 通知");
    }

    // =====================================================================
    // I4 · 网关必败（UC205-3a）：订单保持待支付，座位维持锁定至超时
    // =====================================================================

    @Test
    @DisplayName("I4 网关必败：订单仍待支付、座位保持锁定（未到超时仍 LOCKED）、无明细无通知")
    void i4_gatewayFailureKeepsPendingAndLocked() {
        Double originalRate = bookingConfig.getPayFailRate();
        bookingConfig.setPayFailRate(1.0); // 必败档（NFR2 可配置）
        try {
            Showing showing = freeShowing();
            Seat picked = freeSeats(showing.getId(), 1).get(0);
            TicketBookingService.LockOrderResult lock = ticketBookingService.lockAndCreateOrder(
                    showing.getId(), audience().getId(), List.of(picked.getId()));

            PayOutcome outcome = payFlowService.pay(lock.orderId(), audience().getId());
            assertEquals(PayOutcome.PayOutcomeType.GATEWAY_FAILED, outcome.type(),
                    "必败档应返回网关失败");

            // 订单保持待支付（状态机 payFailed 自环），无支付时间/取票码
            TicketOrder order = ticketOrderRepository.findById(lock.orderId()).orElseThrow();
            assertEquals(OrderState.PENDING_PAYMENT, order.getStatus(), "网关失败订单应保持待支付");
            assertNull(order.getPayTime());
            assertNull(order.getPickupCode());

            // 座位维持锁定至超时：状态/锁定人/截止时间均未变（未到超时不得释放）
            Seat stored = seatRepository.findById(picked.getId()).orElseThrow();
            assertEquals(SeatState.LOCKED, stored.getStatus(), "座位应保持锁定");
            assertEquals(audience().getId(), stored.getLockUserId(), "锁定人应不变");
            assertNotNull(stored.getLockExpireAt(), "锁定截止时间应保留（倒计时继续）");
            assertTrue(stored.getLockExpireAt().isAfter(LocalDateTime.now()),
                    "未到超时，锁定截止时间应仍在未来");
            assertTrue(Math.abs(java.time.Duration.between(lock.lockExpireAt(), stored.getLockExpireAt())
                            .getSeconds()) <= 1,
                    "锁定截止时间不得因支付失败被改动");

            // 无明细、无出票通知
            assertEquals(0, orderItemRepository.findByOrderId(order.getId()).size(), "未出票不得有明细");
            assertEquals(0, noticeCount(audience().getId(), "TICKET_ISSUED"), "未出票不得发出票通知");
        } finally {
            bookingConfig.setPayFailRate(originalRate);
        }
    }

    // =====================================================================
    // I5 · 支付时座位已超时释放（UC205-4a）：作废 + 提示重选 + 无中间态脏数据
    // =====================================================================

    @Test
    @DisplayName("I5 支付时座位已超时释放：支付作废、订单已取消、座位回空闲、模拟退款、无明细")
    void i5_payWhenSeatTimeoutReleasedIsVoided() {
        Showing showing = freeShowing();
        Seat picked = freeSeats(showing.getId(), 1).get(0);
        TicketBookingService.LockOrderResult lock = ticketBookingService.lockAndCreateOrder(
                showing.getId(), audience().getId(), List.of(picked.getId()));

        // 模拟倒计时归零且未被定时任务清理：锁定截止时间已过（与 I2 夹具手法一致）
        Seat stale = seatRepository.findById(picked.getId()).orElseThrow();
        stale.setLockExpireAt(LocalDateTime.now().minusSeconds(60));
        seatRepository.saveAndFlush(stale);

        PayOutcome outcome = payFlowService.pay(lock.orderId(), audience().getId());
        assertEquals(PayOutcome.PayOutcomeType.VOIDED, outcome.type(),
                "座位已被超时释放，支付应作废（UC205-4a）");
        assertTrue(outcome.message().contains("重新选座"), "应提示重新选座：" + outcome.message());

        // 订单维持已取消态（补记事务 cancel：待支付→已取消）
        TicketOrder order = ticketOrderRepository.findById(lock.orderId()).orElseThrow();
        assertEquals(OrderState.CANCELED, order.getStatus(), "作废后订单应为已取消");
        assertNull(order.getPickupCode(), "作废订单不得有取票码");

        // 座位回空闲（补记释放残留锁定），无中间态：不残留 LOCKED/SOLD、无有效明细
        Seat stored = seatRepository.findById(picked.getId()).orElseThrow();
        assertEquals(SeatState.FREE, stored.getStatus(), "残留锁定应被释放回空闲");
        assertNull(stored.getLockUserId());
        assertNull(stored.getLockExpireAt());
        assertEquals(0, orderItemRepository.findByOrderId(order.getId()).size(), "作废订单不得有明细");

        // 网关已扣款（必成档）→ 模拟退款事件广播 → 退款通知落库
        assertEquals(1, noticeCount(audience().getId(), "REFUND_COMPLETED"),
                "支付作废应广播退款事件并落通知");
    }

    // =====================================================================
    // I6 · 状态更新异常（UC205-5a）：整体事务回滚，无脏数据
    // =====================================================================

    /** I6 夹具快照（专用场次 + 订单），供崩溃断言与清理使用。 */
    private record I6Fixture(Long showingId, List<Long> seatIds, Long orderId, long seatVersionAfterLock) {
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 真实提交边界：夹具提交 + 崩溃事务回滚
    @DisplayName("I6 状态更新异常：钩子③/④ 中途抛异常 → 整体回滚，订单/座位/明细/取票码/通知无残留")
    void i6_stateUpdateExceptionRollsBackEverything() {
        I6Fixture fx = null;
        try {
            fx = createI6Fixture();
            runI6CrashAssertions(fx);
        } finally {
            if (fx != null) {
                cleanupI6Fixture(fx);
            }
        }
    }

    /** I6 夹具（独立提交）：专用场次（2 座，普通区）+ 锁座下单。 */
    private I6Fixture createI6Fixture() {
        Movie movie = movieRepository.findAll().get(0);
        var hall = hallRepository.findAll().stream()
                .filter(h -> h.getHallType() == HallType.STANDARD).findFirst().orElseThrow();
        Showing showing = new Showing();
        showing.setMovie(movie);
        showing.setHall(hall);
        showing.setStartTime(LocalDateTime.now().plusHours(2));
        showing.setStatus(ShowingStatus.PUBLISHED);
        showing.setPriceType(PriceType.ORIGINAL);
        showing.setPriceParams("{\"basePrice\":20.00}");
        showing = showingRepository.save(showing);

        Seat seatA = new Seat();
        seatA.setShowing(showing);
        seatA.setRowNum(1);
        seatA.setColNum(1);
        seatA.setZone(SeatZone.NORMAL);
        seatA.setStatus(SeatState.FREE);
        Seat seatB = new Seat();
        seatB.setShowing(showing);
        seatB.setRowNum(1);
        seatB.setColNum(2);
        seatB.setZone(SeatZone.NORMAL);
        seatB.setStatus(SeatState.FREE);
        seatA = seatRepository.save(seatA);
        seatB = seatRepository.save(seatB);
        List<Long> seatIds = List.of(seatA.getId(), seatB.getId());

        TicketBookingService.LockOrderResult lock = ticketBookingService.lockAndCreateOrder(
                showing.getId(), audience().getId(), seatIds);
        long version = seatRepository.findById(seatIds.get(0)).orElseThrow().getVersion();
        return new I6Fixture(showing.getId(), seatIds, lock.orderId(), version);
    }

    /** I6 崩溃断言：状态落库后抛异常 / 事件广播后抛异常 → 两次均整体回滚无脏数据。 */
    private void runI6CrashAssertions(I6Fixture fx) {
        // ---- 崩溃流程①：③ 状态迁移与明细落库完成后抛异常（独立事务回滚） ----
        OnlinePayFlow crashAfterStateChange = new OnlinePayFlow(
                entityManager, objectMapper, priceStrategyResolver,
                new PickupCodeGenerator(code -> false), new MockPayGateway(bookingConfig),
                eventPublisher) {
            @Override
            protected PayOutcome applyStateChange(PayContext context, GatewayResult gateway) {
                PayOutcome outcome = super.applyStateChange(context, gateway);
                throw new IllegalStateException("I6 模拟：状态更新中途异常（UC205-5a）");
            }
        };
        IllegalStateException crash1 = assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                        crashAfterStateChange.execute(new PayCommand(fx.orderId(), audience().getId()))),
                "模拟状态更新中途异常应抛出");
        assertTrue(crash1.getMessage().contains("I6 模拟"));

        // ---- 断言①：整体回滚无脏数据（订单/座位/明细/取票码均回到支付前） ----
        TicketOrder order = ticketOrderRepository.findById(fx.orderId()).orElseThrow();
        assertEquals(OrderState.PENDING_PAYMENT, order.getStatus(), "回滚后订单应仍为待支付");
        assertNull(order.getPickupCode(), "回滚后取票码应不存在");
        assertNull(order.getPayTime(), "回滚后支付时间应不存在");
        assertEquals(0, orderItemRepository.findByOrderId(fx.orderId()).size(), "回滚后不应有明细");
        for (Long seatId : fx.seatIds()) {
            Seat stored = seatRepository.findById(seatId).orElseThrow();
            assertEquals(SeatState.LOCKED, stored.getStatus(), "回滚后座位应仍为锁定（未被售出）");
            assertEquals(fx.seatVersionAfterLock(), stored.getVersion(), "售出更新应随事务回滚（version 不变）");
        }
        assertEquals(0, noticeCount(audience().getId(), "TICKET_ISSUED"),
                "事件未广播（④ 未执行），不得有出票通知");

        // ---- 崩溃流程②：④ 事件广播（通知已写）后抛异常 → 通知随事务一并回滚 ----
        OnlinePayFlow crashAfterPublish = new OnlinePayFlow(
                entityManager, objectMapper, priceStrategyResolver,
                new PickupCodeGenerator(code -> false), new MockPayGateway(bookingConfig),
                eventPublisher) {
            @Override
            protected void publishEvents(PayContext context, PayOutcome outcome) {
                super.publishEvents(context, outcome);
                throw new IllegalStateException("I6 模拟：事件广播后异常");
            }
        };
        assertThrows(IllegalStateException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                        crashAfterPublish.execute(new PayCommand(fx.orderId(), audience().getId()))));

        TicketOrder order2 = ticketOrderRepository.findById(fx.orderId()).orElseThrow();
        assertEquals(OrderState.PENDING_PAYMENT, order2.getStatus(), "回滚后订单应仍为待支付");
        assertEquals(0, orderItemRepository.findByOrderId(fx.orderId()).size(), "回滚后不应有明细");
        assertEquals(0, noticeCount(audience().getId(), "TICKET_ISSUED"),
                "已写通知应随事务回滚（无脏通知）");
    }

    /** I6 清理（独立提交）：删除订单/座位/专用场次，恢复库态供镜像复跑。 */
    private void cleanupI6Fixture(I6Fixture fx) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            ticketOrderRepository.findById(fx.orderId()).ifPresent(ticketOrderRepository::delete);
            fx.seatIds().forEach(seatId -> seatRepository.findById(seatId)
                    .ifPresent(seatRepository::delete));
            showingRepository.findById(fx.showingId()).ifPresent(showingRepository::delete);
        });
    }

    // =====================================================================
    // I7 · 超时释放任务（UC206）：到期座位回空闲 + 释放事件广播（通知落库）+ 订单 CANCELED
    // =====================================================================

    @Test
    @DisplayName("I7 超时释放：直调任务确定性触发——座位回空闲、订单已取消、释放通知落库、幂等")
    void i7_timeoutReleaseReleasesSeatAndCancelsOrder() throws Exception {
        Showing showing = freeShowing();
        Seat picked = freeSeats(showing.getId(), 1).get(0);
        long versionAfterLock;
        TicketBookingService.LockOrderResult lock = ticketBookingService.lockAndCreateOrder(
                showing.getId(), audience().getId(), List.of(picked.getId()));
        versionAfterLock = seatRepository.findById(picked.getId()).orElseThrow().getVersion();

        // 模拟倒计时归零：lock_expire_at 移入过去（扫描条件 lock_expire_at < now 命中）
        Seat stale = seatRepository.findById(picked.getId()).orElseThrow();
        stale.setLockExpireAt(LocalDateTime.now().minusSeconds(1));
        seatRepository.saveAndFlush(stale);

        // @Scheduled 注解存在（task-04 交付要求：定时调度真实挂接）
        assertTrue(LockExpireReleaseTask.class
                        .getDeclaredMethod("releaseExpiredLocks").isAnnotationPresent(Scheduled.class),
                "LockExpireReleaseTask.releaseExpiredLocks 必须标注 @Scheduled");

        long noticesBefore = noticeCount(audience().getId(), "SEAT_RELEASED");

        // 直调服务方法触发（保证确定性；@Scheduled 独立调度已另行验证注解存在）
        releaseTask.releaseExpiredLocks();

        // 座位：回空闲 + 锁定字段清空 + version+1（释放前复查通过后条件更新释放）
        Seat stored = seatRepository.findById(picked.getId()).orElseThrow();
        assertEquals(SeatState.FREE, stored.getStatus(), "到期座位应被释放回空闲");
        assertNull(stored.getLockUserId());
        assertNull(stored.getLockExpireAt());
        assertTrue(stored.getVersion() > versionAfterLock, "释放条件更新应 version+1");

        // 订单：待支付 → 已取消（spec §3.1 行3）
        TicketOrder order = ticketOrderRepository.findById(lock.orderId()).orElseThrow();
        assertEquals(OrderState.CANCELED, order.getStatus(), "超时订单应被取消");

        // 释放事件广播 → 通知订阅者落库
        assertEquals(noticesBefore + 1, noticeCount(audience().getId(), "SEAT_RELEASED"),
                "释放事件应广播并落一条 SEAT_RELEASED 通知");

        // 幂等：再次扫描对已释放座位无作用（无新事件、无状态漂移）
        releaseTask.releaseExpiredLocks();
        assertEquals(SeatState.FREE, seatRepository.findById(picked.getId()).orElseThrow().getStatus());
        assertEquals(OrderState.CANCELED, ticketOrderRepository.findById(lock.orderId())
                .orElseThrow().getStatus());
        assertEquals(noticesBefore + 1, noticeCount(audience().getId(), "SEAT_RELEASED"),
                "重复扫描不得重复发通知");
    }
}
