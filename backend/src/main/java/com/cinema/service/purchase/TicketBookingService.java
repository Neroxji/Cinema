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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

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
 *       惰性释放，事务加入本方法 @Transactional）；</li>
 *   <li>创建 PENDING_PAYMENT 订单：order_no 全库唯一生成（先查后插+唯一索引兜底）；
 *       total_amount 为按座位 zone 经 {@link PriceStrategyResolver} 计价的预估合计
 *       （UC204-5"展示待支付金额"；出票时快照价重新计价，total_amount 以明细合计为准）。</li>
 * </ol>
 */
// @UseCase: UC204/UC205
@Service
public class TicketBookingService {

    private static final Logger log = LoggerFactory.getLogger(TicketBookingService.class);

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

    public TicketBookingService(SeatLockService seatLockService,
                                EntityManager entityManager,
                                ShowingRepository showingRepository,
                                SeatRepository seatRepository,
                                UserRepository userRepository,
                                TicketOrderRepository ticketOrderRepository,
                                PriceStrategyResolver priceStrategyResolver,
                                ObjectMapper objectMapper,
                                DomainEventPublisher eventPublisher) {
        this.seatLockService = seatLockService;
        this.entityManager = entityManager;
        this.showingRepository = showingRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.ticketOrderRepository = ticketOrderRepository;
        this.priceStrategyResolver = priceStrategyResolver;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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
     * 选座锁座并创建待支付订单（UC204 步骤 4~5）。
     *
     * @throws BizException 10005 座位已被占用（并发失败，UC204-4a）/ 10003 参数或状态不符 /
     *                      10004 场次不存在
     */
    @Transactional
    public LockOrderResult lockAndCreateOrder(Long showingId, Long userId, List<Long> seatIds) {
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

    private JsonNode parseParams(String priceParams) {
        try {
            return objectMapper.readTree(priceParams == null ? "{}" : priceParams);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "场次计价参数解析失败：" + priceParams);
        }
    }
}
