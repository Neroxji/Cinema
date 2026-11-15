package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.config.BookingConfig;
import com.cinema.model.CinemaHall;
import com.cinema.model.Movie;
import com.cinema.model.Notification;
import com.cinema.model.OrderItem;
import com.cinema.model.QueueRecord;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.User;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.PriceType;
import com.cinema.model.enums.QueueStatus;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;
import com.cinema.model.enums.ShowingStatus;
import com.cinema.model.enums.UserRole;
import com.cinema.repository.CinemaHallRepository;
import com.cinema.repository.MovieRepository;
import com.cinema.repository.NotificationRepository;
import com.cinema.repository.OrderItemRepository;
import com.cinema.repository.QueueRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.TicketOrderRepository;
import com.cinema.repository.UserRepository;
import com.cinema.service.purchase.TicketBookingService;
import com.cinema.service.purchase.pay.PayFlowService;
import com.cinema.service.purchase.pay.PayOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I8~I12 + 跨模块专项 · C 模块售后链集成测试用例主体（测试计划 §3 I8~I12 + §6 验收场景，P1 模块账）。
 *
 * <p>抽象基类只承载用例，profile 由子类声明（机制照搬 I1~I7）：
 * <ul>
 *   <li>{@link AftermarketIntegrationTest}：H2 内存库 test 档（默认 {@code mvn test} 即跑）</li>
 *   <li>{@link AftermarketMySQLIntegrationTest}：容器 MySQL 8 mysql-test 档
 *       （{@code mvn test -Dtest=AftermarketMySQLIntegrationTest -Dcinema.mysql.tests=true} 显式复跑）</li>
 * </ul>
 *
 * <p>隔离方式与 I1~I7 一致：类级 {@code @Transactional} 回滚，不污染种子数据。
 * 递补超时用例（I9）不真实等待 3 分钟：把邀请通知 created_at 直接改写进过去（编排者契约
 * 决策 3 口径——截止 = 邀请 created_at + 响应时限），再直调 {@link QueueService#sweepExpiredInvites()}
 * （与 I7 直调释放任务同法）。测试数据不提交，后台 @Scheduled 扫描（READ COMMITTED）
 * 不可见未提交数据，扫描路径与测试断言互不干扰。
 */
@SpringBootTest
@Transactional
@DisplayName("I8~I12+跨模块 · C 模块售后链（退票/递补/核销），用例主体")
abstract class AbstractAftermarketIntegrationTest {

    private static final Random RANDOM = new SecureRandom();

    @Autowired
    protected RefundService refundService;
    @Autowired
    protected ChangeShowingService changeShowingService;
    @Autowired
    protected QueueService queueService;
    @Autowired
    protected CheckinService checkinService;
    @Autowired
    protected CommandHistory commandHistory;
    @Autowired
    protected TicketBookingService ticketBookingService;
    @Autowired
    protected PayFlowService payFlowService;
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
    protected QueueRepository queueRepository;
    @Autowired
    protected NotificationRepository notificationRepository;
    @Autowired
    protected EntityManager entityManager;

    // ---------------- 公共夹具 ----------------

    /** 演示观众。 */
    protected User audience() {
        return userRepository.findByUsername("audience").orElseThrow();
    }

    /** 新建一名观众（临时账号，随测试事务回滚）。 */
    protected User newAudienceUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setPasswordHash("x$" + name);
        user.setRole(UserRole.AUDIENCE);
        return userRepository.save(user);
    }

    /**
     * 建一个自造小场次（seats 个手工座位，全部空闲）：复用种子影片/影厅（同影片满足 UC303
     * "限同影片"），座位手工插入（唯一约束仅限场次内），开映时间默认 3 天后（远在退票截止外）。
     */
    protected Showing tinyShowing(int seats) {
        return tinyShowing(movieRepository.findAll().get(0), LocalDateTime.now().plusDays(3), seats);
    }

    protected Showing tinyShowing(LocalDateTime startTime, int seats) {
        return tinyShowing(movieRepository.findAll().get(0), startTime, seats);
    }

    protected Showing tinyShowing(Movie movie, LocalDateTime startTime, int seats) {
        CinemaHall hall = hallRepository.findAll().get(0);
        Showing showing = new Showing();
        showing.setMovie(movie);
        showing.setHall(hall);
        showing.setStartTime(startTime);
        showing.setStatus(ShowingStatus.PUBLISHED);
        showing.setPriceType(PriceType.ORIGINAL);
        showing.setPriceParams("{\"basePrice\":10.00}");
        showing = showingRepository.save(showing);
        for (int i = 1; i <= seats; i++) {
            Seat seat = new Seat();
            seat.setShowing(showing);
            seat.setRowNum(9);
            seat.setColNum(i);
            seat.setZone(SeatZone.NORMAL);
            seat.setStatus(SeatState.FREE);
            seatRepository.save(seat);
        }
        return showing;
    }

    /**
     * 把场次做满（UC304 候补前置）：全部座位置 SOLD，并为 buyer 生成一笔自洽的已出票订单
     * （每座一条有效明细、快照价 10.00、8 位唯一取票码）。
     */
    protected TicketOrder makeFullySold(Showing showing, User buyer) {
        List<Seat> seats = seatRepository.findByShowingId(showing.getId());
        BigDecimal unitPrice = new BigDecimal("10.00");
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(seats.size()));
        TicketOrder order = new TicketOrder();
        order.setOrderNo("ITX" + Long.toHexString(System.nanoTime()));
        order.setPickupCode(nextPickupCode());
        order.setUser(buyer);
        order.setShowing(showing);
        order.setStatus(OrderState.ISSUED);
        order.setPayTime(LocalDateTime.now().minusMinutes(5));
        order.setTotalAmount(total); // IDENTITY 主键：save 即立即 INSERT，总额必须先赋值
        order = ticketOrderRepository.save(order);
        List<OrderItem> items = new java.util.ArrayList<>(seats.size());
        for (Seat seat : seats) {
            seat.setStatus(SeatState.SOLD);
            seatRepository.save(seat);
            OrderItem item = new OrderItem();
            item.setOrder(order);
            item.setSeat(seat);
            item.setPriceSnapshot(unitPrice);
            item.setValidFlag(1L);
            items.add(item);
        }
        orderItemRepository.saveAll(items);
        ticketOrderRepository.flush();
        return order;
    }

    /** 8 位数字取票码（测试夹具内先查后插，uk_ticket_order_pickup_code 全库唯一）。 */
    protected String nextPickupCode() {
        for (int i = 0; i < 20; i++) {
            String code = String.format("%08d", RANDOM.nextInt(100_000_000));
            if (ticketOrderRepository.findByPickupCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("取票码生成连续冲突");
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

    /** 某候补记录的最新递补邀请通知（无则 null）。 */
    protected Notification latestInvite(Long queueId) {
        List<Notification> hits = notificationRepository
                .findByTypeAndContentContainingOrderByIdDesc("QUEUE_MATCHED",
                        QueueService.INVITE_TOKEN_PREFIX + queueId + QueueService.INVITE_TOKEN_SUFFIX,
                        PageRequest.of(0, 1));
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 取某用户在某场次的候补记录（不限状态）。 */
    protected QueueRecord queueRecordOf(Long showingId, Long userId) {
        return entityManager.createQuery("""
                        SELECT q FROM QueueRecord q WHERE q.showing.id = :sid AND q.user.id = :uid
                        """, QueueRecord.class)
                .setParameter("sid", showingId)
                .setParameter("uid", userId)
                .getSingleResult();
    }

    // =====================================================================
    // I8 · 退票且队列非空（UC302-6a）：座位回空闲 + 按 rank 通知第一位
    // =====================================================================

    @Test
    @DisplayName("I8 退票且队列非空：座位回空闲、明细失效、按 rank 仅第一位收到递补邀请")
    void i8_refundWithQueueNotifiesFirstCandidate() {
        // 夹具：2 座满座场（owner 已出票订单）+ 两位候补（rank 1/2）
        Showing showing = tinyShowing(2);
        User owner = audience();
        TicketOrder order = makeFullySold(showing, owner);
        User candidate1 = newAudienceUser("i8q1" + Long.toHexString(System.nanoTime()));
        User candidate2 = newAudienceUser("i8q2" + Long.toHexString(System.nanoTime()));

        // UC304 准入守卫：非满座场次不可候补；满座后按序候补；重复候补拒绝
        Showing notFull = tinyShowing(2);
        BizException notFullRejected = assertThrows(BizException.class,
                () -> queueService.join(notFull.getId(), candidate1.getId()));
        assertEquals(ErrorCode.QUEUE_JOIN_REJECTED.getCode(), notFullRejected.getCode(),
                "非满座场次候补应拒绝 13014");

        QueueService.QueueJoinResult join1 = queueService.join(showing.getId(), candidate1.getId());
        QueueService.QueueJoinResult join2 = queueService.join(showing.getId(), candidate2.getId());
        assertEquals(1, join1.rank(), "第一位候补 rank=1");
        assertEquals(2, join2.rank(), "第二位候补 rank=2（按先来后到）");
        assertEquals(QueueStatus.WAITING, join2.status());
        BizException duplicate = assertThrows(BizException.class,
                () -> queueService.join(showing.getId(), candidate1.getId()));
        assertEquals(ErrorCode.QUEUE_ALREADY_JOINED.getCode(), duplicate.getCode(), "重复候补应拒绝 13003");

        long invitesC1Before = noticeCount(candidate1.getId(), "QUEUE_MATCHED");
        long invitesC2Before = noticeCount(candidate2.getId(), "QUEUE_MATCHED");
        long releaseBefore = noticeCount(owner.getId(), "SEAT_RELEASED");
        long refundBefore = noticeCount(owner.getId(), "REFUND_COMPLETED");

        // 动作：退票（释放 2 座）——观察者链路同事务广播（QueueCheckSubscriber + NoticeSubscriber）
        RefundService.RefundOutcome outcome = refundService.refund(order.getId(), owner.getId());

        // 订单/明细/座位：REFUNDED + valid_flag=NULL + 座位回空闲
        assertEquals(OrderState.REFUNDED, outcome.status());
        TicketOrder refunded = ticketOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderState.REFUNDED, refunded.getStatus(), "订单应为已退票（终态）");
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            assertNull(item.getValidFlag(), "退票后明细应失效（防双卖唯一索引释放，spec §4.4）");
        }
        for (Seat seat : seatRepository.findByShowingId(showing.getId())) {
            assertEquals(SeatState.FREE, seat.getStatus(), "退票后座位应回空闲（可重售）");
        }

        // UC302-6a：按 rank 仅第一位收到递补邀请（内容含递补凭据 queueId），第二位无通知
        assertEquals(invitesC1Before + 1, noticeCount(candidate1.getId(), "QUEUE_MATCHED"),
                "第一位候补应收到递补邀请通知");
        assertEquals(invitesC2Before, noticeCount(candidate2.getId(), "QUEUE_MATCHED"),
                "第二位候补不应提前收到邀请");
        QueueRecord q1 = queueRecordOf(showing.getId(), candidate1.getId());
        Notification invite = latestInvite(q1.getId());
        assertNotNull(invite, "应能按递补凭据令牌检索到邀请通知");
        assertTrue(invite.getContent().contains(QueueService.INVITE_TOKEN_PREFIX + q1.getId()),
                "邀请内容应内嵌递补凭据（含 queueId 供 claim）：" + invite.getContent());
        assertFalse(invite.isReadFlag(), "新通知应为未读");
        assertEquals(QueueStatus.WAITING, q1.getStatus(), "邀请≠递补成功，记录应仍为排队中");
        assertEquals(QueueStatus.WAITING, queueRecordOf(showing.getId(), candidate2.getId()).getStatus());

        // 退票人通知链路（spec §7.7）：座位释放 + 模拟退款到账
        assertEquals(releaseBefore + 1, noticeCount(owner.getId(), "SEAT_RELEASED"),
                "退票应广播释放事件并落 SEAT_RELEASED 通知");
        assertEquals(refundBefore + 1, noticeCount(owner.getId(), "REFUND_COMPLETED"),
                "退票应广播退款事件并落 REFUND_COMPLETED 通知");
    }

    // =====================================================================
    // I9 · 递补：第一位超时未确认→EXPIRED 顺延第二位；时限内确认→直接锁定 + 订单创建
    // =====================================================================

    @Test
    @DisplayName("I9 递补：第一位超时 EXPIRED 顺延第二位；第二位时限内确认 → 锁座 + 待支付订单")
    void i9_expiredFirstAdvancesThenClaimLocksSeat() throws Exception {
        Showing showing = tinyShowing(2);
        User owner = audience();
        TicketOrder order = makeFullySold(showing, owner);
        User candidate1 = newAudienceUser("i9q1" + Long.toHexString(System.nanoTime()));
        User candidate2 = newAudienceUser("i9q2" + Long.toHexString(System.nanoTime()));
        queueService.join(showing.getId(), candidate1.getId());
        queueService.join(showing.getId(), candidate2.getId());

        // 退票 → 第一位 Q1 收到邀请（截止 = 邀请 created_at + 响应时限，决策 3）
        refundService.refund(order.getId(), owner.getId());
        QueueRecord q1 = queueRecordOf(showing.getId(), candidate1.getId());
        QueueRecord q2 = queueRecordOf(showing.getId(), candidate2.getId());
        assertNotNull(latestInvite(q1.getId()), "夹具：第一位应已收到邀请");
        queueService.claimDeadlineOf(q1.getId()).ifPresent(deadline ->
                assertTrue(deadline.isAfter(LocalDateTime.now()), "截止时间应在未来（未超时窗口）"));

        // 模拟超时：邀请 created_at 改写进过去（决策 3 口径：created_at+时限 已过）
        Notification invite = latestInvite(q1.getId());
        invite.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        notificationRepository.saveAndFlush(invite);

        // @Scheduled 注解存在（定时扫描真实挂接，交付要求同 I7）；直调保证确定性
        Method sweep = QueueService.class.getDeclaredMethod("sweepExpiredInvites");
        assertTrue(sweep.isAnnotationPresent(Scheduled.class),
                "QueueService.sweepExpiredInvites 必须标注 @Scheduled");
        queueService.sweepExpiredInvites();

        // 顺延断言：第一位 EXPIRED，第二位收到新邀请（仍 WAITING）
        assertEquals(QueueStatus.EXPIRED, queueRecordOf(showing.getId(), candidate1.getId()).getStatus(),
                "超时未确认的第一位应被置 EXPIRED");
        assertNotNull(latestInvite(q2.getId()), "顺延后第二位应收到递补邀请");
        assertEquals(QueueStatus.WAITING, queueRecordOf(showing.getId(), candidate2.getId()).getStatus());

        // 超时者补救确认：拒绝 13005（懒惰检查兜底）
        BizException expired = assertThrows(BizException.class,
                () -> queueService.claim(q1.getId(), candidate1.getId()));
        assertEquals(ErrorCode.QUEUE_CLAIM_EXPIRED.getCode(), expired.getCode(), "超时确认应拒绝 13005");

        // 时限内确认（第二位）：直接锁定座位 + 创建 PENDING_PAYMENT 订单（复用 B 模块编排）
        QueueService.QueueClaimResult claim = queueService.claim(q2.getId(), candidate2.getId());
        assertEquals(QueueStatus.MATCHED, claim.status(), "确认后候补记录应为 MATCHED");
        assertNotNull(claim.orderId(), "应创建待支付订单");
        assertNotNull(claim.orderNo());
        assertTrue(claim.lockExpireAt().isAfter(LocalDateTime.now()), "锁定截止时间应在未来");

        TicketOrder claimedOrder = ticketOrderRepository.findById(claim.orderId()).orElseThrow();
        assertEquals(OrderState.PENDING_PAYMENT, claimedOrder.getStatus(), "递补订单应为待支付");
        assertEquals(candidate2.getId(), claimedOrder.getUser().getId(), "订单应归递补观众");
        assertEquals(showing.getId(), claimedOrder.getShowing().getId());
        Seat claimedSeat = seatRepository.findById(claim.seatId()).orElseThrow();
        assertEquals(SeatState.LOCKED, claimedSeat.getStatus(), "递补座位应被直接锁定（UC305）");
        assertEquals(candidate2.getId(), claimedSeat.getLockUserId(), "锁定人应为递补观众");
        assertEquals(QueueStatus.MATCHED, queueRecordOf(showing.getId(), candidate2.getId()).getStatus());

        // 重复确认：MATCHED 状态不符拒绝（13004）
        BizException repeated = assertThrows(BizException.class,
                () -> queueService.claim(q2.getId(), candidate2.getId()));
        assertEquals(ErrorCode.QUEUE_RECORD_INVALID.getCode(), repeated.getCode(), "重复确认应拒绝 13004");

        // 他人候补记录越权：12005
        BizException forbidden = assertThrows(BizException.class,
                () -> queueService.claim(q2.getId(), candidate1.getId()));
        assertEquals(ErrorCode.ORDER_FORBIDDEN.getCode(), forbidden.getCode(), "越权确认应拒绝 12005");
    }

    // =====================================================================
    // I10 · 队列为空（UC302-6b）：座位直接可售，无递补通知
    // =====================================================================

    @Test
    @DisplayName("I10 退票且队列为空：座位回空闲、全场无任何递补邀请通知")
    void i10_refundWithEmptyQueueHasNoInvite() {
        Showing showing = tinyShowing(2);
        User owner = audience();
        TicketOrder order = makeFullySold(showing, owner);

        long queueMatchedBefore = noticeCount(owner.getId(), "QUEUE_MATCHED");
        long releaseBefore = noticeCount(owner.getId(), "SEAT_RELEASED");

        refundService.refund(order.getId(), owner.getId());

        for (Seat seat : seatRepository.findByShowingId(showing.getId())) {
            assertEquals(SeatState.FREE, seat.getStatus(), "无候补时座位直接回空闲可售");
        }
        // 全库无递补邀请：通知订阅者只落退票人两件通知，QueueCheckSubscriber 无动作（UC302-6b）
        Long invites = entityManager.createQuery(
                        "SELECT COUNT(n) FROM Notification n WHERE n.type = 'QUEUE_MATCHED'", Long.class)
                .getSingleResult();
        assertEquals(0L, invites, "队列为空不应产生任何递补邀请通知");
        assertEquals(queueMatchedBefore, noticeCount(owner.getId(), "QUEUE_MATCHED"));
        assertEquals(releaseBefore + 1, noticeCount(owner.getId(), "SEAT_RELEASED"));
    }

    // =====================================================================
    // I11 · 正常核销（UC307）：ISSUED→CHECKED_IN + checkin_time 记录 + 幂等拒绝
    // =====================================================================

    @Test
    @DisplayName("I11 正常核销：凭取票码/订单号核销成功记录时间与操作员；重复核销/核销后退票拒绝")
    void i11_checkinNormalRecordsTimeAndOperator() {
        Showing showing = tinyShowing(1);
        User owner = audience();
        TicketOrder order = makeFullySold(showing, owner);

        // 凭取票码核销（UC307 基本路径 1）：成功 + 记录核销时间与操作员
        CheckinService.CheckinOutcome outcome = checkinService.checkin(null, order.getPickupCode(), "checker01");
        assertEquals("CHECKED_IN", outcome.status(), "核销后应为已检票（终态）");
        assertNotNull(outcome.checkinTime(), "应记录核销时间");
        assertEquals("checker01", outcome.operator(), "操作员应在响应返回（契约决策 2）");
        assertEquals(showing.getId(), outcome.showingId());
        assertEquals(1, outcome.seats().size());

        TicketOrder stored = ticketOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderState.CHECKED_IN, stored.getStatus(), "落库应为 CHECKED_IN");
        assertNotNull(stored.getCheckinTime(), "checkin_time 应落库");

        // 重复核销（UC307-5a 幂等）：凭订单号再核销 → 13008（同时覆盖订单号检索路径）
        BizException repeated = assertThrows(BizException.class,
                () -> checkinService.checkin(order.getOrderNo(), null, "checker01"));
        assertEquals(ErrorCode.CHECKIN_ALREADY_CHECKED_IN.getCode(), repeated.getCode(), "重复核销应拒绝 13008");

        // 凭据缺失 → 10003；凭据无效 → 12004
        assertEquals(ErrorCode.INVALID_PARAM.getCode(),
                assertThrows(BizException.class, () -> checkinService.checkin(null, null, "checker01")).getCode());
        assertEquals(ErrorCode.ORDER_NOT_FOUND.getCode(),
                assertThrows(BizException.class,
                        () -> checkinService.checkin(null, nextPickupCode(), "checker01")).getCode());

        // 验收场景"核销后再次退票"：已检票为终态 → 13001 拒绝
        BizException refundRejected = assertThrows(BizException.class,
                () -> refundService.refund(order.getId(), owner.getId()));
        assertEquals(ErrorCode.ORDER_NOT_REFUNDABLE.getCode(), refundRejected.getCode(),
                "已检票订单退票应拒绝 13001");
        assertTrue(refundRejected.getMessage().contains("已检票"), "原因应指明已检票：" + refundRejected.getMessage());
    }

    // =====================================================================
    // I12 · 开映超 30 分钟核销（UC307-3b）：转人工提示（拒绝）
    // =====================================================================

    @Test
    @DisplayName("I12 开映超 30 分钟核销：拒绝 13009 转人工，订单状态不变")
    void i12_lateCheckinRequiresManualHandling() {
        // 开映 1 小时前的场次（已超 30 分钟宽限）
        Showing showing = tinyShowing(LocalDateTime.now().minusHours(1), 1);
        User owner = audience();
        TicketOrder order = makeFullySold(showing, owner);

        BizException late = assertThrows(BizException.class,
                () -> checkinService.checkin(order.getOrderNo(), null, "checker01"));
        assertEquals(ErrorCode.CHECKIN_MANUAL_REQUIRED.getCode(), late.getCode(), "超 30 分钟应拒绝 13009");
        assertTrue(late.getMessage().contains("人工"), "提示应含转人工口径：" + late.getMessage());

        // 状态不变：仍为已出票（拒绝仅拦截，不做任何迁移）
        assertEquals(OrderState.ISSUED, ticketOrderRepository.findById(order.getId())
                .orElseThrow().getStatus(), "被拒绝的核销不得改变订单状态");
        assertNull(ticketOrderRepository.findById(order.getId()).orElseThrow().getCheckinTime());
    }

    // =====================================================================
    // UC303 · 改签全链（实现自验 + 验收场景"第二次改签拒绝"）
    // =====================================================================

    @Test
    @DisplayName("UC303 改签：原座位释放+新场次锁座出票+留痕；二次改签/异影片/过截止拒绝")
    void uc303_changeFlowAndGuards() {
        Movie movie = movieRepository.findAll().get(0);
        Showing original = tinyShowing(movie, LocalDateTime.now().plusDays(3), 2);
        Showing target = tinyShowing(movie, LocalDateTime.now().plusDays(4), 2);
        Showing otherMovieShowing = tinyShowing(movieRepository.findAll().get(1),
                LocalDateTime.now().plusDays(4), 2);
        User owner = audience();
        TicketOrder order = makeFullySold(original, owner);

        // 过改签截止：开映前 2 小时内的订单不可改签
        TicketOrder nearStart = makeFullySold(tinyShowing(movie, LocalDateTime.now().plusHours(1), 1), owner);
        assertEquals(ErrorCode.CHANGE_DEADLINE_PASSED.getCode(),
                assertThrows(BizException.class,
                        () -> changeShowingService.change(nearStart.getId(), owner.getId(), target.getId()))
                        .getCode(), "过改签截止应拒绝 13011");
        ticketOrderRepository.findById(nearStart.getId()).ifPresent(o ->
                assertEquals(OrderState.ISSUED, o.getStatus(), "被拒绝的改签不得改变订单状态"));

        // 限同影片：异影片场次拒绝 13012
        assertEquals(ErrorCode.CHANGE_TARGET_INVALID.getCode(),
                assertThrows(BizException.class,
                        () -> changeShowingService.change(order.getId(), owner.getId(), otherMovieShowing.getId()))
                        .getCode(), "异影片目标场次应拒绝 13012");
        assertEquals(OrderState.ISSUED, ticketOrderRepository.findById(order.getId())
                .orElseThrow().getStatus(), "被拒绝的改签不得改变订单状态");

        // 正常改签：原座位释放 + 新场次锁座出票 + change_from_showing_id 留痕
        long releaseBeforeChange = noticeCount(owner.getId(), "SEAT_RELEASED");
        ChangeShowingService.ChangeOutcome outcome =
                changeShowingService.change(order.getId(), owner.getId(), target.getId());
        assertEquals(OrderState.ISSUED, outcome.status(), "改签后订单保持已出票（指向新场次）");
        assertEquals(target.getId(), outcome.newShowingId());
        assertEquals(2, outcome.newSeatIds().size());
        assertEquals(0, new BigDecimal("20.00").compareTo(outcome.newAmount()),
                "改签后总额=新明细合计（新场次策略计价，差价不结算）");

        TicketOrder changed = ticketOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(target.getId(), changed.getShowing().getId(), "订单应指向新场次");
        assertEquals(target.getId(), changed.getChangeFromShowingId(), "改签来源应留痕");
        assertNotNull(changed.getPickupCode(), "改签沿用原取票码");
        assertEquals(0, new BigDecimal("20.00").compareTo(changed.getTotalAmount()),
                "改签后总额=新明细合计（新场次策略计价）");
        List<OrderItem> validItems = orderItemRepository.findByOrderIdAndValidFlag(order.getId(), 1L);
        assertEquals(2, validItems.size(), "新场次应有 2 条有效明细");
        for (OrderItem item : validItems) {
            assertEquals(target.getId(), item.getSeat().getShowing().getId(), "有效明细应属新场次");
        }
        for (Seat seat : seatRepository.findByShowingId(original.getId())) {
            assertEquals(SeatState.FREE, seat.getStatus(), "原场次座位应释放回空闲");
        }
        for (Seat seat : seatRepository.findByShowingId(target.getId())) {
            assertEquals(SeatState.SOLD, seat.getStatus(), "新场次座位应已售");
        }
        assertEquals(releaseBeforeChange + 1, noticeCount(owner.getId(), "SEAT_RELEASED"),
                "改签应广播原场次释放事件（CHANGE_SHOWING，候补检查照常触发）");

        // 验收场景"第二次改签"：每单限 1 次 → 13013
        Showing anotherTarget = tinyShowing(movie, LocalDateTime.now().plusDays(5), 2);
        BizException secondChange = assertThrows(BizException.class,
                () -> changeShowingService.change(order.getId(), owner.getId(), anotherTarget.getId()));
        assertEquals(ErrorCode.CHANGE_LIMIT_REACHED.getCode(), secondChange.getCode(),
                "第二次改签应拒绝 13013（每单限 1 次）");
        assertEquals(target.getId(), ticketOrderRepository.findById(order.getId())
                .orElseThrow().getShowing().getId(), "被拒绝的二次改签不得改变指向");
    }

    // =====================================================================
    // 命令模式 undo（spec §5：RefundCommand/ChangeShowingCommand 均 undo，接 CommandHistory）
    // =====================================================================

    @Test
    @DisplayName("命令模式：退票/改签记入 CommandHistory；undo 补偿恢复；座位被重售后撤销拒绝")
    void commandHistoryUndoRestoresRefundAndChange() {
        commandHistory.clear(); // 单例栈跨用例累积，先清零隔离

        Showing showing = tinyShowing(2);
        User owner = audience();
        TicketOrder order = makeFullySold(showing, owner);

        // 退票入栈 + undo 补偿恢复
        refundService.refund(order.getId(), owner.getId());
        assertEquals(1, commandHistory.size(), "退票命令应记入 CommandHistory");
        assertEquals("RefundCommand", commandHistory.commandNames().get(0));
        commandHistory.undoLast();
        TicketOrder restored = ticketOrderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderState.ISSUED, restored.getStatus(), "撤销退票应恢复已出票");
        for (OrderItem item : orderItemRepository.findByOrderIdAndValidFlag(order.getId(), 1L)) {
            assertEquals(SeatState.SOLD, seatRepository.findById(item.getSeat().getId())
                    .orElseThrow().getStatus(), "撤销退票应恢复座位已售");
            assertNotNull(item.getValidFlag(), "撤销退票应恢复明细有效");
        }
        assertEquals(0, commandHistory.size(), "undo 成功后命令应出栈");

        // 撤销拒绝：退票后座位被他人重新购买（B 模块锁座+支付链复用）→ undo 守卫拒绝
        refundService.refund(order.getId(), owner.getId());
        Seat freedSeat = seatRepository.findByShowingId(showing.getId()).get(0);
        User rebuyer = newAudienceUser("i9rb" + Long.toHexString(System.nanoTime()));
        TicketBookingService.LockOrderResult lock =
                ticketBookingService.lockAndCreateOrder(showing.getId(), rebuyer.getId(), List.of(freedSeat.getId()));
        assertEquals(PayOutcome.PayOutcomeType.SUCCESS,
                payFlowService.pay(lock.orderId(), rebuyer.getId()).type(), "他人应能重新购买释放的座位");
        BizException undoRejected = assertThrows(BizException.class, commandHistory::undoLast,
                "座位已被重售，撤销退票应拒绝");
        assertTrue(undoRejected.getMessage().contains("重新购买")
                        || undoRejected.getMessage().contains("重新锁定"),
                "拒绝原因应指明座位已被占用：" + undoRejected.getMessage());
        commandHistory.clear(); // 清栈，避免污染后续断言

        // 改签入栈 + undo 补偿换回原场次（用全新场次夹具：上一阶段的座位已被重购者占用）
        Movie movie = movieRepository.findAll().get(0);
        Showing changeFrom = tinyShowing(movie, LocalDateTime.now().plusDays(3), 2);
        Showing target = tinyShowing(movie, LocalDateTime.now().plusDays(4), 2);
        TicketOrder changeOrder = makeFullySold(changeFrom, owner);
        changeShowingService.change(changeOrder.getId(), owner.getId(), target.getId());
        assertEquals("ChangeShowingCommand", commandHistory.commandNames().get(0), "改签命令应记入历史");
        commandHistory.undoLast();
        TicketOrder reverted = ticketOrderRepository.findById(changeOrder.getId()).orElseThrow();
        assertEquals(changeFrom.getId(), reverted.getShowing().getId(), "撤销改签应指回原场次");
        assertNull(reverted.getChangeFromShowingId(), "撤销改签应还原改签留痕");
        assertEquals(OrderState.ISSUED, reverted.getStatus());
        for (Seat seat : seatRepository.findByShowingId(changeFrom.getId())) {
            assertEquals(SeatState.SOLD, seat.getStatus(), "撤销改签应恢复原座位已售");
        }
        for (Seat seat : seatRepository.findByShowingId(target.getId())) {
            assertEquals(SeatState.FREE, seat.getStatus(), "撤销改签应释放新场次座位");
        }
    }

    // =====================================================================
    // 跨模块专项（验收门）：满座场退票 → 递补通知 → claim 锁座下单 → B 段支付出票
    // =====================================================================

    @Test
    @DisplayName("跨模块专项：满座场退票→第一位候补受邀→claim 锁座+待支付订单→支付出票")
    void crossModule_refundQueueClaimPay_fullChain() {
        // 种子满座场（IMAX 50 座全售）+ 种子已出票订单（演示观众）
        Showing fullHouse = showingRepository.findAll().stream()
                .filter(s -> seatRepository.findByShowingId(s.getId()).stream()
                        .allMatch(seat -> seat.getStatus() == SeatState.SOLD))
                .findFirst().orElseThrow();
        TicketOrder seedOrder = ticketOrderRepository.findAll().stream()
                .filter(o -> o.getShowing().getId().equals(fullHouse.getId()))
                .findFirst().orElseThrow();

        User candidate1 = newAudienceUser("xm1" + Long.toHexString(System.nanoTime()));
        User candidate2 = newAudienceUser("xm2" + Long.toHexString(System.nanoTime()));
        queueService.join(fullHouse.getId(), candidate1.getId());
        queueService.join(fullHouse.getId(), candidate2.getId());

        // 第 1 步：退票（模块 C）→ 全场座位回空闲
        refundService.refund(seedOrder.getId(), audience().getId());
        for (Seat seat : seatRepository.findByShowingId(fullHouse.getId())) {
            assertEquals(SeatState.FREE, seat.getStatus(), "退票后全场座位应空闲");
        }

        // 第 2 步：第一位候补收到递补通知（UC302-6a → UC305）
        QueueRecord q1 = queueRecordOf(fullHouse.getId(), candidate1.getId());
        Notification invite = latestInvite(q1.getId());
        assertNotNull(invite, "第一位候补应收到递补通知");
        assertTrue(invite.getContent().contains(QueueService.INVITE_TOKEN_PREFIX + q1.getId()));

        // 第 3 步：claim 确认递补（UC305）→ 座位锁定 + 新 PENDING_PAYMENT 订单（复用 SeatLockService/订单编排）
        QueueService.QueueClaimResult claim = queueService.claim(q1.getId(), candidate1.getId());
        assertEquals(QueueStatus.MATCHED, claim.status());
        TicketOrder claimOrder = ticketOrderRepository.findById(claim.orderId()).orElseThrow();
        assertEquals(OrderState.PENDING_PAYMENT, claimOrder.getStatus());
        assertEquals(candidate1.getId(), claimOrder.getUser().getId());
        assertEquals(SeatState.LOCKED, seatRepository.findById(claim.seatId()).orElseThrow().getStatus());

        // 第 4 步：支付出票（复用 B 段 pay 接口 PayFlowService，UC205）
        PayOutcome pay = payFlowService.pay(claim.orderId(), candidate1.getId());
        assertEquals(PayOutcome.PayOutcomeType.SUCCESS, pay.type(), "递补订单支付应成功：" + pay.message());
        TicketOrder issued = ticketOrderRepository.findById(claim.orderId()).orElseThrow();
        assertEquals(OrderState.ISSUED, issued.getStatus(), "支付后订单已出票");
        assertNotNull(issued.getPickupCode());
        assertTrue(issued.getPickupCode().matches("^\\d{8}$"), "出票应生成 8 位取票码");
        assertEquals(SeatState.SOLD, seatRepository.findById(claim.seatId()).orElseThrow().getStatus(),
                "支付后座位已售");

        // 全链通知：递补邀请（C）+ 出票成功（B→观察者）
        assertEquals(1, noticeCount(candidate1.getId(), "TICKET_ISSUED"), "支付出票应落出票通知");
        assertNotNull(latestInvite(q1.getId()), "递补邀请仍在");
        assertEquals(QueueStatus.WAITING, queueRecordOf(fullHouse.getId(), candidate2.getId()).getStatus(),
                "第二位候补继续排队");
        assertNull(latestInvite(queueRecordOf(fullHouse.getId(), candidate2.getId()).getId()),
                "第二位未被邀请（无新的释放事件）");
    }
}
