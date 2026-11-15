package com.cinema.service.purchase;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.event.DomainEventPublisher;
import com.cinema.common.event.SeatReleasedEvent;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatState;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.TicketOrderRepository;
import com.cinema.repository.UserRepository;
import com.cinema.service.admin.strategy.PriceStrategy;
import com.cinema.service.admin.strategy.PriceStrategyResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 观众购票编排服务（UC204 锁座接口编排：校验→锁定→创建待支付订单）。
 *
 * <p>// @UseCase: UC204(选座锁座+下单), @UseCase: UC205 前置（待支付订单）
 *
 * <p>编排步骤（spec §4 步骤 1~5 由 {@link SeatLockService} 承载，本类扩展编排）：
 * <ol>
 *   <li>重选放弃（同场次重复锁座时）：先把本人该场次既有 PENDING_PAYMENT 订单取消、
 *       其仍持有的锁定座位条件更新释放（UC204 主动放弃语义，GIVE_UP 事件广播）——
 *       保证"同一用户在同一场次至多一笔待支付订单"，支付时的座位集合才无歧义；</li>
 *   <li>调用 {@link SeatLockService#lockSeats}（复用 task-03：事务外预检/条件更新/
 *       惰性释放，事务加入本方法圈定的事务）；</li>
 *   <li>创建 PENDING_PAYMENT 订单：order_no 全库唯一生成（先查后插+唯一索引兜底）；
 *       total_amount 为按座位 zone 经 {@link PriceStrategyResolver} 计价的预估合计
 *       （UC204-5"展示待支付金额"；出票时快照价重新计价，total_amount 以明细合计为准）。</li>
 * </ol>
 *
 * <p><b>锁座建单事务的有界重试</b>（task-08 打回修复，A6 并发撞号根治）：
 * {@link #lockAndCreateOrder} 入口本身<b>非事务方法</b>，事务边界改由 {@link TransactionTemplate}
 * 在 {@code doLockAndCreateOrder} 周围显式圈定（REQUIRED，与改造前 @Transactional 语义等价）。
 * 动因：H2 2.x IDENTITY 生成器是事务性的——身份号随事务回滚"回收"复用，且并发事务可能读到
 * 相同的已提交序列值，50 个并发建单 INSERT 偶发撞 ticket_order 主键（A6 契约=全部成功，
 * 无法像 P1 注册夹具那样串行化绕开）。故当异常<b>可归因于 ticket_order 表唯一约束冲突</b>
 * （主键身份号撞号 / 订单号撞号，见 {@link #isTicketOrderUniqueKeyCollision}）且本方法开启的是
 * 独立新事务时，整体回滚后有界重试（至多 2 次重试）：座位条件更新天然幂等可重入（回滚后座位
 * 回到 FREE，重试按 WHERE status=空闲 AND version=? 重新锁定；若座位已被他人抢占则条件更新
 * 影响 0 行 → 10005 座位已被占用，不引入超卖）；其余异常一律原样上抛（不吞真实系统异常）。
 * 外层事务参与者（如 UC305 候补递补 {@code QueueService.claim}）不重试，保持原整体回滚语义。
 */
// @UseCase: UC204/UC205
@Service
public class TicketBookingService {

    private static final Logger log = LoggerFactory.getLogger(TicketBookingService.class);

    /**
     * 锁座建单事务尝试上限（首次 + 至多 2 次重试，仅针对可归因的 ticket_order 唯一约束撞号）。
     * H2 2.x 并发 IDENTITY 撞号为小概率事件（实测 gate 全量跑约 1/3 概率命中 1 次），
     * 有界 3 次即可将残余失败率压到可忽略量级，同时保证真实缺陷不被无限重试掩盖。
     */
    private static final int LOCK_ORDER_MAX_ATTEMPTS = 3;

    /** 重试归因日志 code（排障检索锚点，grep TX_UNIQUE_CONFLICT）。 */
    static final String RETRY_LOG_CODE_RETRIED = "TX_UNIQUE_CONFLICT_RETRIED";
    /** 重试耗尽日志 code（最终仍失败时原样上抛，不吞异常）。 */
    static final String RETRY_LOG_CODE_EXHAUSTED = "TX_UNIQUE_CONFLICT_EXHAUSTED";

    /** 订单号格式（本卡决策）：B + 17 位时间戳(yyyyMMddHHmmssSSS) + 4 位随机数 = 22 字符 ≤32。 */
    static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private static final Random RANDOM = new SecureRandom();

    private final SeatLockService seatLockService;
    private final EntityManager entityManager;
    private final ShowingRepository showingRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final TicketOrderRepository ticketOrderRepository;
    private final PriceStrategyResolver priceStrategyResolver;
    private final ObjectMapper objectMapper;
    private final DomainEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public TicketBookingService(SeatLockService seatLockService,
                                EntityManager entityManager,
                                ShowingRepository showingRepository,
                                SeatRepository seatRepository,
                                UserRepository userRepository,
                                TicketOrderRepository ticketOrderRepository,
                                PriceStrategyResolver priceStrategyResolver,
                                ObjectMapper objectMapper,
                                DomainEventPublisher eventPublisher,
                                PlatformTransactionManager transactionManager) {
        this.seatLockService = seatLockService;
        this.entityManager = entityManager;
        this.showingRepository = showingRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.ticketOrderRepository = ticketOrderRepository;
        this.priceStrategyResolver = priceStrategyResolver;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        // 与 SeatLockService 同口径：显式模板圈定事务（REQUIRED），
        // 使入口方法可保持非事务、以容纳"整体回滚后有界重试"的外层循环
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 锁座下单结果（返回给选座页：倒计时与待支付金额展示用）。 */
    public record LockOrderResult(Long orderId, String orderNo, Long showingId, String showingTitle,
                                  LocalDateTime lockExpireAt, java.math.BigDecimal estimatedAmount,
                                  List<SeatPriceView> seats, OrderState status) {

        /** 座位明细视图（座位 id/排/座/分区/预估单价）。 */
        public record SeatPriceView(Long seatId, Integer rowNum, Integer colNum,
                                    com.cinema.model.enums.SeatZone zone, java.math.BigDecimal price) {
        }
    }

    /**
     * 选座锁座并创建待支付订单（UC204 步骤 4~5）——有界重试入口（非事务方法）。
     *
     * <p>事务边界由 {@link TransactionTemplate} 在每次尝试内部圈定（REQUIRED）：
     * 调用方无事务（HTTP 锁座接口）则开启新事务，重试发生时先整体回滚再起新事务；
     * 调用方已有事务（如 UC305 候补递补）则加入外层事务，且异常一律原样上抛、
     * 不做重试（保持参与者原语义，防"已在回滚-only 事务内重试"的无效循环）。
     *
     * <p>重试范围（严格归因，避免吞真实系统异常）：仅当 ① 本次尝试开启了独立新事务、
     * ② 异常因果链可归因为 ticket_order 表唯一约束冲突（H2 IDENTITY 并发撞号 /
     * order_no 撞号，见 {@link #isTicketOrderUniqueKeyCollision}）时才整体回滚重试；
     * A5 语义的"座位已被占用"（BizException 10005）、参数/状态类业务异常、其余运行时
     * 异常均不重试。日志以 code={@code TX_UNIQUE_CONFLICT_RETRIED} /
     * {@code TX_UNIQUE_CONFLICT_EXHAUSTED} 记录归因证据。
     *
     * @throws BizException 10005 座位已被占用（并发失败，UC204-4a）/ 10003 参数或状态不符 /
     *                      10004 场次不存在
     */
    public LockOrderResult lockAndCreateOrder(Long showingId, Long userId, List<Long> seatIds) {
        for (int attempt = 1; ; attempt++) {
            AtomicBoolean freshTransaction = new AtomicBoolean(false);
            try {
                return transactionTemplate.execute(txStatus -> {
                    freshTransaction.set(txStatus.isNewTransaction());
                    return doLockAndCreateOrder(showingId, userId, seatIds);
                });
            } catch (RuntimeException failure) {
                boolean attributed = isTicketOrderUniqueKeyCollision(failure);
                if (freshTransaction.get() && attributed && attempt < LOCK_ORDER_MAX_ATTEMPTS) {
                    log.warn("[UC204][code={}] 锁座建单事务第 {}/{} 次尝试因 ticket_order 唯一约束撞号整体回滚"
                                    + "（H2 IDENTITY 事务快照语义：身份号被回滚/并发事务复用），座位条件更新幂等可重入，"
                                    + "重试不引入超卖，开始第 {} 次尝试；归因：{} <- 根因消息：{}",
                            RETRY_LOG_CODE_RETRIED, attempt, LOCK_ORDER_MAX_ATTEMPTS, attempt + 1,
                            failure.getClass().getName(), rootCauseMessage(failure));
                    continue;
                }
                if (freshTransaction.get() && attributed) {
                    log.error("[UC204][code={}] 锁座建单连续 {} 次尝试均撞 ticket_order 唯一约束，"
                            + "按系统异常原样上抛（不吞异常）；根因消息：{}", RETRY_LOG_CODE_EXHAUSTED,
                            attempt, rootCauseMessage(failure), failure);
                }
                throw failure;
            }
        }
    }

    /**
     * 单次锁座建单事务体（由 {@link TransactionTemplate} 圈定）：放弃重选 → 锁座 → 计价 → 建单。
     * 任何异常传播出模板即整体回滚（座位条件更新回滚后回到 FREE，天然幂等可重入）。
     */
    private LockOrderResult doLockAndCreateOrder(Long showingId, Long userId, List<Long> seatIds) {
        abandonStalePendingOrders(showingId, userId);

        // 复用 task-03 锁座并发方案（事务外预检/条件更新/惰性释放；TransactionTemplate REQUIRED 加入本事务）
        SeatLockService.LockResult lock = seatLockService.lockSeats(showingId, userId, seatIds);

        // 按座位 zone 计价（复用策略解析器，勿重写公式）——锁座时为预估价，出票时重新快照
        Showing showing = showingRepository.findById(showingId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "场次不存在：" + showingId));
        PriceStrategy strategy = priceStrategyResolver.of(showing.getPriceType());
        JsonNode params = parseParams(showing.getPriceParams());

        List<Seat> seats = seatRepository.findAllById(lock.seatIds()).stream()
                .sorted(Comparator.comparing(Seat::getId))
                .toList();
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        List<LockOrderResult.SeatPriceView> views = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            java.math.BigDecimal price = strategy.calculate(seat.getZone(), params);
            views.add(new LockOrderResult.SeatPriceView(seat.getId(), seat.getRowNum(),
                    seat.getColNum(), seat.getZone(), price));
            total = total.add(price);
        }

        // 创建 PENDING_PAYMENT 订单（order_no 全库唯一生成，先查后插 + 唯一索引兜底）
        TicketOrder order = new TicketOrder();
        order.setOrderNo(nextUniqueOrderNo());
        order.setUser(userRepository.getReferenceById(userId));
        order.setShowing(showing);
        order.setStatus(OrderState.PENDING_PAYMENT);
        order.setTotalAmount(total);
        order = ticketOrderRepository.save(order);

        String movieTitle = showing.getMovie().getTitle();
        log.info("[UC204] 锁座下单成功：orderNo={}，用户 {} 在场次 {} 锁定 {} 座，预估 ¥{}，锁定至 {}",
                order.getOrderNo(), userId, showingId, seats.size(), total, lock.lockExpireAt());
        return new LockOrderResult(order.getId(), order.getOrderNo(), showingId, movieTitle,
                lock.lockExpireAt(), total, views, order.getStatus());
    }

    /**
     * 重选放弃：取消本人该场次全部待支付订单，并释放其仍持有的锁定座位
     * （UC204 主动放弃语义；GIVE_UP 事件广播 → 通知订阅者落库）。
     * 保证"一用户一场次至多一笔待支付订单"不变式——支付流程按（场次+锁定人）
     * 采样座位集合的前提。
     */
    private void abandonStalePendingOrders(Long showingId, Long userId) {
        List<TicketOrder> pendingOrders = entityManager.createQuery("""
                        SELECT o FROM TicketOrder o
                         WHERE o.showing.id = :showingId AND o.user.id = :userId
                           AND o.status = :status
                         ORDER BY o.id
                        """, TicketOrder.class)
                .setParameter("showingId", showingId)
                .setParameter("userId", userId)
                .setParameter("status", OrderState.PENDING_PAYMENT)
                .getResultList();
        if (pendingOrders.isEmpty()) {
            return;
        }
        Long abandonedOrderId = pendingOrders.get(0).getId();
        List<Long> pendingOrderIds = pendingOrders.stream().map(TicketOrder::getId).toList();

        // 释放本人仍持有的锁定座位（含已过期未清理的：统一按放弃处理）
        List<Seat> held = entityManager.createQuery("""
                        SELECT s FROM Seat s
                         WHERE s.showing.id = :showingId AND s.lockUserId = :userId
                           AND s.status = :locked
                         ORDER BY s.id
                        """, Seat.class)
                .setParameter("showingId", showingId)
                .setParameter("userId", userId)
                .setParameter("locked", SeatState.LOCKED)
                .getResultList();
        if (!held.isEmpty()) {
            entityManager.createQuery("""
                            UPDATE Seat s
                               SET s.status = :toStatus, s.lockExpireAt = NULL,
                                   s.lockUserId = NULL, s.version = s.version + 1
                             WHERE s.id IN :ids AND s.status = :expectedStatus
                               AND s.lockUserId = :userId
                            """)
                    .setParameter("toStatus", SeatState.FREE)
                    .setParameter("expectedStatus", SeatState.LOCKED)
                    .setParameter("ids", held.stream().map(Seat::getId).toList())
                    .setParameter("userId", userId)
                    .executeUpdate();
        }
        entityManager.flush();
        entityManager.clear();

        // 状态机迁移：待支付→已取消（clear 后按 id 重载）
        for (Long orderId : pendingOrderIds) {
            TicketOrder order = entityManager.find(TicketOrder.class, orderId);
            if (order != null && order.getStatus() == OrderState.PENDING_PAYMENT) {
                order.cancel();
            }
        }
        eventPublisher.publish(new SeatReleasedEvent(abandonedOrderId, showingId, userId,
                held.stream().map(Seat::getId).toList(),
                SeatReleasedEvent.ReleaseReason.GIVE_UP, LocalDateTime.now()));
        log.info("[UC204] 重选放弃：用户 {} 在场次 {} 放弃订单 {}，释放座位 {} 个",
                userId, showingId, pendingOrderIds, held.size());
    }

    /**
     * 订单号生成（先查后插 + 冲突重试 + 唯一索引 uk_ticket_order_no 兜底）：
     * {@code B + yyyyMMddHHmmssSSS + 4 位随机}（22 字符）。
     */
    private String nextUniqueOrderNo() {
        for (int attempt = 1; attempt <= 5; attempt++) {
            String candidate = "B" + ORDER_NO_TIME.format(LocalDateTime.now())
                    + String.format("%04d", RANDOM.nextInt(10000));
            Long count = entityManager.createQuery(
                            "SELECT COUNT(o) FROM TicketOrder o WHERE o.orderNo = :no", Long.class)
                    .setParameter("no", candidate)
                    .getSingleResult();
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.SYSTEM_ERROR, "订单号生成连续冲突，请重试");
    }

    // =====================================================================
    // ticket_order 唯一约束撞号归因（有界重试的前置判定，task-08 打回修复）
    // =====================================================================

    /**
     * 判定异常是否<b>可归因</b>为 ticket_order 表的唯一约束冲突（主键身份号撞号 / 订单号撞号）。
     *
     * <p>归因标准（双数据库方言，两个条件须同时成立）：
     * <ol>
     *   <li>因果链上存在完整性约束违规标记：Spring {@link DataIntegrityViolationException}
     *       （含子类 DuplicateKeyException，仓库代理翻译产物）或 JDBC
     *       {@link SQLIntegrityConstraintViolationException}（H2 的
     *       JdbcSQLIntegrityConstraintViolationException 即其子类，MySQL 驱动 1062 亦映射至此）；</li>
     *   <li>因果链上任一异常消息同时提及表名 ticket_order 与主键/唯一冲突关键字——
     *       H2：{@code Unique index or primary key violation: "PRIMARY KEY ON public.ticket_order(id) …"}；
     *       MySQL：{@code Duplicate entry '…' for key 'ticket_order.PRIMARY'}。</li>
     * </ol>
     * 外键违规（消息为 Referential integrity / foreign key constraint fails，不含上述关键字）、
     * 值超长等其他完整性异常不满足条件 2，不会被误归因重试——避免吞掉真实系统异常。
     */
    static boolean isTicketOrderUniqueKeyCollision(Throwable error) {
        boolean integrityViolation = false;
        boolean ticketOrderUniqueConflict = false;
        // 深度上限防因果链成环（防御式，正常链深 < 10）
        Throwable t = error;
        for (int depth = 0; t != null && depth < 20; depth++) {
            if (t instanceof DataIntegrityViolationException
                    || t instanceof SQLIntegrityConstraintViolationException) {
                integrityViolation = true;
            }
            String message = t.getMessage();
            if (message != null) {
                String m = message.toLowerCase(Locale.ROOT);
                if (m.contains("ticket_order")
                        && (m.contains("primary key") || m.contains("unique index")
                            || m.contains("duplicate entry"))) {
                    ticketOrderUniqueConflict = true;
                }
            }
            t = t.getCause();
        }
        return integrityViolation && ticketOrderUniqueConflict;
    }

    /** 取因果链最深处的异常消息（归因日志用：H2/MySQL 驱动的原始冲突描述在链尾；深度上限防成环）。 */
    private static String rootCauseMessage(Throwable error) {
        Throwable deepest = error;
        for (int depth = 0; depth < 20 && deepest.getCause() != null; depth++) {
            deepest = deepest.getCause();
        }
        return deepest.getClass().getName() + ": " + deepest.getMessage();
    }

    private JsonNode parseParams(String priceParams) {
        try {
            return objectMapper.readTree(priceParams == null ? "{}" : priceParams);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "场次计价参数解析失败：" + priceParams);
        }
    }
}
