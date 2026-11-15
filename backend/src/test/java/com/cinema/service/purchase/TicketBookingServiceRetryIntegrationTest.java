package com.cinema.service.purchase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.User;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatState;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.TicketOrderRepository;
import com.cinema.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * task-08 打回修复回归 · A6 并发建单 H2 IDENTITY 撞号的有界重试（故障注入验证）。
 *
 * <p>背景：A6（50 并发锁不同座位）在 H2 2.x 下偶发撞 ticket_order 主键（IDENTITY 事务
 * 快照语义，gate 全量跑实测约 1/3 概率命中），概率性失败无法在回归套件中稳定复现。
 * 本类用 Mockito 故障注入把"第一次建单 INSERT 撞主键"变成确定性事件，验证
 * {@link TicketBookingService#lockAndCreateOrder} 的三件事实：
 * <ol>
 *   <li>可归因的 ticket_order 唯一约束撞号 → 整体回滚后有界重试（座位条件更新幂等可重入，
 *       重试重新锁定并建单成功，不引入超卖）；</li>
 *   <li>不可归因的业务异常（座位占用 10005）→ 不重试、原样上抛（A5 语义不受影响）；</li>
 *   <li>完整性违规但不可归因（外键消息）→ 不重试、原样上抛（不吞真实系统异常）。</li>
 * </ol>
 *
 * <p>隔离方式：独立 H2 内存库（{@code cinema-booking-retry-test}，内联 properties 覆盖数据源
 * URL，不新增 yml），与 test 档（{@code cinema-test}）及 api-test 档互不可见；本类
 * <b>故意不加类级 @Transactional</b>——重试仅在本服务开启独立新事务时触发（外层事务参与者
 * 不重试，见 {@code TicketBookingService} javadoc），测试事务内注入故障不会走重试路径。
 * 产生的数据落在本类专属内存库内，随 JVM 退出释放，不污染种子断言。
 */
@SpringBootTest(properties = "spring.datasource.url="
        + "jdbc:h2:mem:cinema-booking-retry-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;"
        + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@DisplayName("task-08 修复回归 · A6 撞号有界重试（故障注入，独立 H2 内存库）")
class TicketBookingServiceRetryIntegrationTest {

    @SpyBean
    private TicketOrderRepository ticketOrderRepository;

    @Autowired
    private TicketBookingService ticketBookingService;

    /** 事务内真实落库用（Spring Data 代理的 save 为抽象方法无法 thenCallRealMethod，
     *  以 persist+flush 复刻 SimpleJpaRepository.save 对新实体的语义：等价真实 INSERT）。 */
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private ShowingRepository showingRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private UserRepository userRepository;

    private Logger bookingLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void captureBookingLogs() {
        bookingLogger = (Logger) LoggerFactory.getLogger(TicketBookingService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        bookingLogger.addAppender(logAppender);
        // 清空 spy 既有记录（首个执行的方法会把上下文启动期 DataSeeder 的 save 计入窗口）
        org.mockito.Mockito.clearInvocations(ticketOrderRepository);
    }

    @AfterEach
    void detachLogCapture() {
        bookingLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    // ---------------- 用例 ----------------

    @Test
    @DisplayName("R1 首次建单撞 ticket_order 主键（gate 实录同形异常）→ 回滚重试成功、座位终态锁定、重试日志归因恰一次")
    void firstInsertCollidesPk_thenRollbackAndRetrySucceeds() {
        User audience = userRepository.findByUsername("audience").orElseThrow();
        Showing showing = allFreeShowing();
        Seat seat = firstFreeSeat(showing.getId());

        // 故障注入：第一次 save 抛 gate 实录同形的 H2 主键撞号异常链，其后放行真实落库
        stubSaveFirstCallThrows(h2PrimaryKeyCollision(54L));

        TicketBookingService.LockOrderResult result = ticketBookingService.lockAndCreateOrder(
                showing.getId(), audience.getId(), List.of(seat.getId()));

        // 重试成功：订单创建且为待支付态
        assertNotNull(result.orderId(), "重试后应成功建单");
        assertEquals(OrderState.PENDING_PAYMENT, result.status());
        verify(ticketOrderRepository, times(2)).save(any(TicketOrder.class));

        // 回滚重试语义：座位在重试事务中重新锁定并归属本用户（不引入超卖）
        Seat after = seatRepository.findById(seat.getId()).orElseThrow();
        assertEquals(SeatState.LOCKED, after.getStatus(), "重试后座位应为锁定态");
        assertEquals(audience.getId(), after.getLockUserId(), "座位锁定人应为下单用户");

        // 订单真实落库（重试事务提交，非桩返回值）
        TicketOrder persisted = ticketOrderRepository.findById(result.orderId()).orElseThrow();
        assertEquals(OrderState.PENDING_PAYMENT, persisted.getStatus());
        assertEquals(showing.getId(), persisted.getShowing().getId());

        // 重试归因日志（code=TX_UNIQUE_CONFLICT_RETRIED）恰一次，无耗尽日志
        assertEquals(1, logCount(TicketBookingService.RETRY_LOG_CODE_RETRIED),
                "应恰有一条重试归因日志（第 1 次尝试回滚重试）");
        assertEquals(0, logCount(TicketBookingService.RETRY_LOG_CODE_EXHAUSTED),
                "重试成功不应出现耗尽日志");
    }

    @Test
    @DisplayName("R2 非归因业务异常（座位占用 10005）不重试：仅一次尝试即原样上抛，A5 语义不变")
    void bizExceptionIsNotRetried() {
        User audience = userRepository.findByUsername("audience").orElseThrow();
        Showing showing = allFreeShowing();
        Seat seat = firstFreeSeat(showing.getId());

        // 故障注入：save 抛业务异常（非完整性约束，不可归因）
        doThrow(new BizException(ErrorCode.SEAT_OCCUPIED))
                .when(ticketOrderRepository).save(any(TicketOrder.class));

        BizException thrown = assertThrows(BizException.class, () -> ticketBookingService
                .lockAndCreateOrder(showing.getId(), audience.getId(), List.of(seat.getId())));
        assertEquals(ErrorCode.SEAT_OCCUPIED.getCode(), thrown.getCode(), "应原样上抛 10005");

        verify(ticketOrderRepository, times(1)).save(any(TicketOrder.class));
        assertEquals(0, logCount(TicketBookingService.RETRY_LOG_CODE_RETRIED), "业务异常不应触发重试");

        // 事务整体回滚：座位保持空闲
        assertEquals(SeatState.FREE, seatRepository.findById(seat.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("R3 完整性违规但不可归因（外键消息引用 ticket_order）不重试：原样上抛不吞真实异常")
    void foreignKeyViolationIsNotRetried() {
        User audience = userRepository.findByUsername("audience").orElseThrow();
        Showing showing = allFreeShowing();
        Seat seat = firstFreeSeat(showing.getId());

        // 外键违规消息也含表名 ticket_order，但不含主键/唯一冲突关键字 → 不得归因重试
        org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException fk =
                new org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException(
                        "Referential integrity constraint violation: \"FK_TICKET_ORDER_USER: "
                                + "PUBLIC.TICKET_ORDER FOREIGN KEY(USER_ID) REFERENCES PUBLIC.USER(ID)\"",
                        "23503", "INSERT INTO ticket_order VALUES (...)", 1452, null, null);
        DataIntegrityViolationException unattributed =
                new DataIntegrityViolationException("could not execute statement", fk);
        doThrow(unattributed).when(ticketOrderRepository).save(any(TicketOrder.class));

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> ticketBookingService.lockAndCreateOrder(showing.getId(), audience.getId(),
                        List.of(seat.getId())));
        assertSame(unattributed, thrown, "应原样上抛同一异常实例（不吞不换型）");

        verify(ticketOrderRepository, times(1)).save(any(TicketOrder.class));
        assertEquals(0, logCount(TicketBookingService.RETRY_LOG_CODE_RETRIED), "不可归因异常不应触发重试");
    }

    // ---------------- 夹具 ----------------

    /**
     * 首次 {@code save} 抛指定异常、其后调用走真实落库（persist+flush，与
     * {@code SimpleJpaRepository.save} 对新实体语义等价——Spring Data 代理的
     * 接口方法是抽象的，无法 {@code thenCallRealMethod}，以应答器委托复刻）。
     */
    private void stubSaveFirstCallThrows(RuntimeException firstFailure) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                throw firstFailure;
            }
            TicketOrder order = invocation.getArgument(0);
            entityManager.persist(order);
            entityManager.flush();
            return order;
        }).when(ticketOrderRepository).save(any(TicketOrder.class));
    }

    // ---------------- 夹具 ----------------

    /** 取一个全部座位空闲的种子场次（种子有若干非满座场次，与 I1/I2 夹具口径一致）。 */
    private Showing allFreeShowing() {
        return showingRepository.findAll().stream()
                .filter(s -> seatRepository.findByShowingId(s.getId()).stream()
                        .allMatch(seat -> seat.getStatus() == SeatState.FREE))
                .findFirst().orElseThrow();
    }

    /** 该场次第一个空闲座位（seat.id 升序，服务内规整后口径一致）。 */
    private Seat firstFreeSeat(Long showingId) {
        return seatRepository.findByShowingId(showingId).stream()
                .filter(seat -> seat.getStatus() == SeatState.FREE)
                .findFirst().orElseThrow();
    }

    /** 造 gate 实录同形的 H2 主键撞号异常链（Spring 翻译层 → H2 驱动，消息含表名 + PRIMARY KEY）。 */
    private static DataIntegrityViolationException h2PrimaryKeyCollision(long key) {
        org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException h2 =
                new org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException(
                        "Unique index or primary key violation: \"PRIMARY KEY ON public.ticket_order(id)"
                                + " ( /* key:" + key + " */ ...)\"",
                        "22504", "INSERT INTO ticket_order VALUES (" + key + ", ...)", 23505, null, null);
        return new DataIntegrityViolationException(
                "could not execute statement; SQL [n/a]; constraint [PRIMARY]", h2);
    }

    /** 统计捕获日志中含指定 code 的事件数。 */
    private long logCount(String code) {
        return logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(code))
                .count();
    }
}
