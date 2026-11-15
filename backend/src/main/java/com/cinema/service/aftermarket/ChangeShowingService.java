package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.common.event.DomainEventPublisher;
import com.cinema.common.event.SeatReleasedEvent;
import com.cinema.config.BookingConfig;
import com.cinema.model.OrderItem;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.ShowingStatus;
import com.cinema.repository.OrderItemRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.TicketOrderRepository;
import com.cinema.service.admin.strategy.PriceStrategy;
import com.cinema.service.admin.strategy.PriceStrategyResolver;
import com.cinema.service.aftermarket.command.ChangeShowingCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 改签服务（UC303，命令模式 · Receiver 角色——ChangeShowingCommand 封装调用）。
 *
 * <p>// @Pattern: Command(Receiver角色——改签业务实现,
 * <br>ConcreteCommand=ChangeShowingCommand), @UseCase: UC303(改签至同影片其他场次)
 *
 * <p>改签语义（spec §3.1 行7 / 任务卡 UC303：限同影片、开映前 2 小时、每单限 1 次）：
 * <ol>
 *   <li>校验（{@link ChangeValidator} 纯逻辑：本人/已出票/未改签过/未过截止）+
 *       目标场次校验（同影片其他场次、PUBLISHED、未开映、余座充足）；</li>
 *   <li>原场次座位释放：明细 valid_flag 置 NULL + 座位 releaseByChange（SOLD→FREE）；</li>
 *   <li>新场次锁座出票：复用 {@link com.cinema.service.purchase.SeatLockService} 条件更新锁定
 *       （并发失败整体回滚，原座位随事务恢复）+ 新场次策略重新计价重建有效明细；</li>
 *   <li>状态机改签自迁移：change_from_showing_id 留痕（每单限 1 次的判定依据）+
 *       showing 指向新场次，状态保持 ISSUED、取票码沿用；</li>
 *   <li>广播 {@link SeatReleasedEvent}(CHANGE_SHOWING) → 候补递补检查 + 通知订阅者。</li>
 * </ol>
 *
 * <p>差价处理（spec 沉默 → 最简实现，已记录字段表-C模块.md）：新票按新场次票价策略重新
 * 计价，total_amount 重置为新明细合计，<b>差价不结算</b>（不补款不退款，演示口径）。
 *
 * <p>选座策略（spec 沉默 → 最简实现，已记录）：优先目标场次"同排同座号"的空闲座位，
 * 不足时按 seat.id 升序取其余空闲座位补足；无足够余座拒绝改签。
 */
// @Pattern: Command(Receiver), @UseCase: UC303
@Service
public class ChangeShowingService {

    private static final Logger log = LoggerFactory.getLogger(ChangeShowingService.class);

    private final TicketOrderRepository ticketOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatRepository seatRepository;
    private final ShowingRepository showingRepository;
    private final com.cinema.service.purchase.SeatLockService seatLockService;
    private final PriceStrategyResolver priceStrategyResolver;
    private final BookingConfig bookingConfig;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final DomainEventPublisher eventPublisher;
    private final CommandHistory commandHistory;

    public ChangeShowingService(TicketOrderRepository ticketOrderRepository,
                                OrderItemRepository orderItemRepository,
                                SeatRepository seatRepository,
                                ShowingRepository showingRepository,
                                com.cinema.service.purchase.SeatLockService seatLockService,
                                PriceStrategyResolver priceStrategyResolver,
                                BookingConfig bookingConfig,
                                ObjectMapper objectMapper,
                                EntityManager entityManager,
                                DomainEventPublisher eventPublisher,
                                CommandHistory commandHistory) {
        this.ticketOrderRepository = ticketOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.seatRepository = seatRepository;
        this.showingRepository = showingRepository;
        this.seatLockService = seatLockService;
        this.priceStrategyResolver = priceStrategyResolver;
        this.bookingConfig = bookingConfig;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.eventPublisher = eventPublisher;
        this.commandHistory = commandHistory;
    }

    /** 改签结果（含撤销补偿所需快照：原场次座位与原金额）。 */
    public record ChangeOutcome(Long orderId, String orderNo, Long userId,
                                Long originalShowingId, List<Long> originalSeatIds, BigDecimal originalAmount,
                                Long newShowingId, List<Long> newSeatIds, BigDecimal newAmount,
                                Long changeFromShowingId, OrderState status) {
    }

    /**
     * 申请改签（UC303 对外入口）：以 {@link ChangeShowingCommand} 执行并记入命令历史（spec §5）。
     *
     * @throws BizException 12004 订单不存在 / 12005 非本人 / 13010 状态不可改签 /
     *                      13013 已改签过 / 13011 已过改签截止 / 11005 目标场次不存在 /
     *                      13012 目标场次无效 / 10005 锁座并发失败（整体回滚）
     */
    @Transactional
    public ChangeOutcome change(Long orderId, Long requesterId, Long targetShowingId) {
        ChangeShowingCommand command = new ChangeShowingCommand(this, orderId, requesterId, targetShowingId);
        command.execute(); // 命令模式：Invoker 触发 ConcreteCommand → Receiver.doChange（结果同时填入命令快照）
        commandHistory.record(command);
        return command.outcome();
    }

    /**
     * 改签业务实现（Receiver 方法，由 ChangeShowingCommand.execute 调用；事务由 change() 圈定）。
     */
    @Transactional
    public ChangeOutcome doChange(Long orderId, Long requesterId, Long targetShowingId) {
        LocalDateTime now = LocalDateTime.now();
        TicketOrder order = ticketOrderRepository.findById(orderId)
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderId));
        Showing original = order.getShowing();
        // 步骤1a：UC303 规则校验（纯逻辑：本人/已出票/每单限 1 次/开映前 2 小时）
        ChangeValidator.assertChangeable(order.getStatus(), order.getUser().getId(), requesterId,
                order.getChangeFromShowingId() != null, original.getStartTime(), now,
                bookingConfig.getRefundDeadline());

        // 步骤1b：目标场次校验——同影片其他场次、已发布、未开映（UC303"限同影片"）
        Showing target = showingRepository.findById(targetShowingId)
                .orElseThrow(() -> new BizException(ErrorCode.SHOWING_NOT_FOUND,
                        "改签目标场次不存在：" + targetShowingId));
        boolean targetValid = !target.getId().equals(original.getId())
                && target.getMovie().getId().equals(original.getMovie().getId())
                && target.getStatus() == ShowingStatus.PUBLISHED
                && target.getStartTime().isAfter(now);
        if (!targetValid) {
            throw new BizException(ErrorCode.CHANGE_TARGET_INVALID);
        }

        List<OrderItem> validItems = orderItemRepository.findByOrderIdAndValidFlag(orderId, 1L);
        List<Long> originalSeatIds = validItems.stream().map(item -> item.getSeat().getId()).sorted().toList();

        // 选座：优先目标场次同排同座号空闲座位，不足按 seat.id 升序补足（最简实现，字段表记录）
        List<Long> chosenSeatIds = chooseTargetSeats(target.getId(), validItems);
        if (chosenSeatIds.size() < validItems.size()) {
            throw new BizException(ErrorCode.CHANGE_TARGET_INVALID, "改签目标场次余座不足（需 "
                    + validItems.size() + " 座）");
        }

        Long userId = order.getUser().getId();
        String orderNo = order.getOrderNo();
        BigDecimal originalAmount = order.getTotalAmount();

        // 步骤2：原场次座位释放（明细失效 + 状态机 releaseByChange：SOLD→FREE）
        for (OrderItem item : validItems) {
            item.setValidFlag(null);
        }
        for (Long seatId : originalSeatIds) {
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "座位不存在：" + seatId));
            seat.releaseByChange();
        }
        entityManager.flush();
        entityManager.clear();

        // 步骤3：新场次锁座（复用 task-03 条件更新并发方案；失败异常传播 → 整体回滚，原座位随事务恢复）
        com.cinema.service.purchase.SeatLockService.LockResult lock =
                seatLockService.lockSeats(target.getId(), userId, chosenSeatIds);

        // 步骤3a：立即出票（改签单本已处于 ISSUED，无二次支付环节）——条件更新逐座售出
        // （与 OnlinePayFlow 售出语句同口径：WHERE 锁定+本人+version，影响行数=0 即并发失败整体回滚）
        for (Long seatId : lock.seatIds()) {
            Seat locked = entityManager.find(Seat.class, seatId);
            int sold = entityManager.createQuery("""
                            UPDATE Seat s
                               SET s.status = :toStatus, s.lockExpireAt = NULL,
                                   s.lockUserId = NULL, s.version = s.version + 1
                             WHERE s.id = :id AND s.status = :expectedStatus
                               AND s.version = :expectedVersion AND s.lockUserId = :userId
                            """)
                    .setParameter("toStatus", SeatState.SOLD)
                    .setParameter("expectedStatus", SeatState.LOCKED)
                    .setParameter("expectedVersion", locked.getVersion())
                    .setParameter("userId", userId)
                    .setParameter("id", seatId)
                    .executeUpdate();
            if (sold == 0) {
                throw new BizException(ErrorCode.SEAT_OCCUPIED,
                        "座位 " + seatId + " 在改签出票期间状态变化，请重试");
            }
        }
        entityManager.flush();
        entityManager.clear();

        // 步骤3b：新明细重建（新场次策略重新计价快照；total=新明细合计，差价不结算——决策记录）
        Showing freshTarget = entityManager.find(Showing.class, target.getId());
        TicketOrder freshOrder = entityManager.find(TicketOrder.class, orderId);
        PriceStrategy strategy = priceStrategyResolver.of(freshTarget.getPriceType());
        JsonNode params = parseParams(freshTarget.getPriceParams());
        BigDecimal newTotal = BigDecimal.ZERO;
        for (Long seatId : lock.seatIds()) {
            Seat seat = entityManager.find(Seat.class, seatId);
            OrderItem item = new OrderItem();
            item.setOrder(freshOrder);
            item.setSeat(seat);
            BigDecimal snapshot = strategy.calculate(seat.getZone(), params);
            item.setPriceSnapshot(snapshot);
            item.setValidFlag(1L);
            entityManager.persist(item);
            newTotal = newTotal.add(snapshot);
        }
        freshOrder.setTotalAmount(newTotal);

        // 步骤4：状态机改签自迁移（change_from_showing_id 留痕）+ showing 指向新场次（跨聚合编排）
        freshOrder.changeShowing(target.getId());
        freshOrder.setShowing(freshTarget);
        entityManager.flush();
        entityManager.clear();

        // 步骤5：广播原场次座位释放事件 → 候补递补检查 + 通知订阅者（同事务）
        eventPublisher.publish(new SeatReleasedEvent(orderId, original.getId(), userId, originalSeatIds,
                SeatReleasedEvent.ReleaseReason.CHANGE_SHOWING, now));
        log.info("[UC303] 改签成功：orderNo={} 用户 {} 场次 {}→{}，原 {} 座释放，新 {} 座已出票，"
                        + "金额 ¥{}→¥{}（差价不结算）",
                orderNo, userId, original.getId(), target.getId(),
                originalSeatIds.size(), lock.seatIds().size(), originalAmount, newTotal);
        return new ChangeOutcome(orderId, orderNo, userId,
                original.getId(), originalSeatIds, originalAmount,
                target.getId(), lock.seatIds(), newTotal,
                target.getId(), OrderState.ISSUED);
    }

    /**
     * 撤销改签（ChangeShowingCommand.undo 调用的补偿实现，不经 HTTP 暴露）：
     * 守卫 = 订单仍处改签后状态（ISSUED 且 change_from_showing_id=新场次）、新座位仍为本单
     * 有效明细、原座位全部空闲且无他人有效明细；补偿 = 释放新座位 + 原座位恢复已售 +
     * 原明细复位 + showing/change_from_showing_id 还原。
     */
    @Transactional
    public void undoChange(ChangeOutcome outcome) {
        TicketOrder order = ticketOrderRepository.findById(outcome.orderId())
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + outcome.orderId()));
        if (order.getStatus() != OrderState.ISSUED
                || !outcome.newShowingId().equals(order.getChangeFromShowingId())) {
            throw new BizException(ErrorCode.INVALID_PARAM,
                    "订单已不在改签后状态，无法撤销改签（状态：" + order.getStatus() + "）");
        }
        List<Long> currentValidSeatIds = orderItemRepository.findByOrderIdAndValidFlag(outcome.orderId(), 1L)
                .stream().map(item -> item.getSeat().getId()).sorted().toList();
        if (!currentValidSeatIds.equals(outcome.newSeatIds())) {
            throw new BizException(ErrorCode.INVALID_PARAM, "改签后座位已发生变更（被检票/退票），无法撤销改签");
        }
        for (Long seatId : outcome.originalSeatIds()) {
            Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "座位不存在：" + seatId));
            if (seat.getStatus() != SeatState.FREE
                    || orderItemRepository.existsBySeatIdAndValidFlag(seatId, 1L)) {
                throw new BizException(ErrorCode.INVALID_PARAM,
                        "原座位 " + seatId + " 已被重新售出/锁定，无法撤销改签");
            }
        }

        // 补偿①：新场次座位释放（明细失效 + 条件更新 SOLD→FREE，乐观锁 version+1）
        for (OrderItem item : orderItemRepository.findByOrderIdAndValidFlag(outcome.orderId(), 1L)) {
            item.setValidFlag(null);
        }
        for (Long seatId : outcome.newSeatIds()) {
            releaseSoldSeat(seatId, "撤销改签释放新座位");
        }
        // 补偿②：原座位恢复已售（WHERE status=空闲 原子守卫）+ 原明细复位有效
        for (Long seatId : outcome.originalSeatIds()) {
            int restored = entityManager.createQuery("""
                            UPDATE Seat s SET s.status = :toStatus, s.version = s.version + 1
                             WHERE s.id = :id AND s.status = :fromStatus
                            """)
                    .setParameter("toStatus", SeatState.SOLD)
                    .setParameter("fromStatus", SeatState.FREE)
                    .setParameter("id", seatId)
                    .executeUpdate();
            if (restored == 0) {
                throw new BizException(ErrorCode.INVALID_PARAM,
                        "原座位 " + seatId + " 状态已变化，无法撤销改签");
            }
        }
        Showing originalShowing = entityManager.find(Showing.class, outcome.originalShowingId());
        TicketOrder freshOrder = entityManager.find(TicketOrder.class, outcome.orderId());
        PriceStrategy strategy = priceStrategyResolver.of(originalShowing.getPriceType());
        JsonNode params = parseParams(originalShowing.getPriceParams());
        BigDecimal restoredTotal = BigDecimal.ZERO;
        for (OrderItem item : orderItemRepository.findByOrderIdAndValidFlagIsNull(outcome.orderId())) {
            item.setValidFlag(1L);
            Seat seat = entityManager.find(Seat.class, item.getSeat().getId());
            BigDecimal snapshot = strategy.calculate(seat.getZone(), params);
            item.setPriceSnapshot(snapshot);
            restoredTotal = restoredTotal.add(snapshot);
        }
        freshOrder.setTotalAmount(restoredTotal);
        // 补偿③：状态机无"取消改签"迁移，undo 直接还原指向与留痕（命令补偿语义）
        freshOrder.setShowing(originalShowing);
        freshOrder.setChangeFromShowingId(null);
        // 批量 JPQL 绕过持久化上下文：flush 固化 + clear 防后续读到缓存中的旧座位状态（代码库统一口径）
        entityManager.flush();
        entityManager.clear();
        log.info("[UC303-undo] 撤销改签补偿完成：orderId={} 还原至场次 {}，{} 座恢复已售",
                outcome.orderId(), outcome.originalShowingId(), outcome.originalSeatIds().size());
    }

    // ---------------- 内部方法 ----------------

    /**
     * 目标场次选座：优先"同排同座号"空闲座位（观感最贴近原票），缺失或被占则按 seat.id
     * 升序取其余空闲座位补足（spec 沉默处的最简实现）。
     */
    private List<Long> chooseTargetSeats(Long targetShowingId, List<OrderItem> validItems) {
        List<Seat> targetSeats = seatRepository.findByShowingId(targetShowingId);
        Map<String, Seat> freeByPosition = new HashMap<>();
        List<Seat> freeOrdered = new ArrayList<>();
        for (Seat seat : targetSeats) {
            if (seat.getStatus() == SeatState.FREE) {
                freeByPosition.put(seat.getRowNum() + ":" + seat.getColNum(), seat);
                freeOrdered.add(seat);
            }
        }
        freeOrdered.sort(java.util.Comparator.comparing(Seat::getId));

        List<Long> chosen = new ArrayList<>(validItems.size());
        List<Long> fallback = new ArrayList<>(freeOrdered.stream().map(Seat::getId).toList());
        for (OrderItem item : validItems) {
            Seat samePosition = freeByPosition.get(item.getSeat().getRowNum() + ":" + item.getSeat().getColNum());
            if (samePosition != null && chosen.stream().noneMatch(id -> id.equals(samePosition.getId()))) {
                chosen.add(samePosition.getId());
                fallback.remove(samePosition.getId());
            }
        }
        for (Long seatId : fallback) {
            if (chosen.size() >= validItems.size()) {
                break;
            }
            if (!chosen.contains(seatId)) {
                chosen.add(seatId);
            }
        }
        return chosen.stream().sorted().toList();
    }

    /** 条件更新释放已售座位（SOLD→FREE，乐观锁 version+1）；影响行数=0 即并发变化。 */
    private void releaseSoldSeat(Long seatId, String scenario) {
        int released = entityManager.createQuery("""
                        UPDATE Seat s
                           SET s.status = :toStatus, s.lockExpireAt = NULL, s.lockUserId = NULL,
                               s.version = s.version + 1
                         WHERE s.id = :id AND s.status = :expectedStatus
                        """)
                .setParameter("toStatus", SeatState.FREE)
                .setParameter("expectedStatus", SeatState.SOLD)
                .setParameter("id", seatId)
                .executeUpdate();
        if (released == 0) {
            throw new BizException(ErrorCode.INVALID_PARAM, scenario + "失败：座位 " + seatId + " 状态已变化");
        }
    }

    private JsonNode parseParams(String priceParams) {
        try {
            return objectMapper.readTree(priceParams == null ? "{}" : priceParams);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "场次计价参数解析失败：" + priceParams);
        }
    }
}
