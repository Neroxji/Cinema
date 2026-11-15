package com.cinema.service.aftermarket;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.CinemaHall;
import com.cinema.model.Movie;
import com.cinema.model.OrderItem;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.OrderState;
import com.cinema.model.enums.SeatZone;
import com.cinema.repository.OrderItemRepository;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.TicketOrderRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检票核销服务（UC307，task-05——A8 并发幂等的实现载体）。
 *
 * <p>// @UseCase: UC307(检票核销——幂等)+扩展3a/3b/5a, @UseCase: A8(重复核销并发仅一次生效)
 *
 * <p>语义口径（spec §3.1 行5 / PRD UC307）：
 * <ul>
 *   <li>凭订单号或取票码检索（两者至少其一，均传时优先订单号）；</li>
 *   <li>仅 ISSUED 可核销：已检票重复核销 → 13008（幂等拒绝，UC307-5a 仅一次生效）；
 *       待支付/已退票/已取消 → 13007（UC307-3a 状态不符拒绝展示原因）；</li>
 *   <li>UC307-3b：开映超过 30 分钟转人工（宽限常量 {@link #CHECKIN_GRACE}，PRD 固定值，
 *       spec 未列为配置项故不作 NFR2 配置——字段表记录）；</li>
 *   <li><b>并发幂等（A8）</b>：核销迁移以条件更新原子化
 *       {@code UPDATE ticket_order SET status='CHECKED_IN', checkin_time=?
 *        WHERE id=? AND status='ISSUED'}——与状态机 Issued.checkIn 的迁移语义逐字等价，
 *       WHERE 原子化使并发核销有且仅有一个生效，其余按重读状态返回 13008/13007；
 *       （ticket_order 无 @Version 列，实体脏检查不具备并发守卫能力，故走条件更新。）</li>
 *   <li>操作员记录（编排者契约决策 2）：ticket_order 无 operator 列且不扩表（守 9 表口径）——
 *       操作员写入应用日志并包含在核销响应中返回；"核销记录含操作员"的 spec §1 缺口已记入
 *       字段表-C模块.md 与晨报，是否补列由用户定夺。</li>
 * </ul>
 */
// @UseCase: UC307
@Service
public class CheckinService {

    private static final Logger log = LoggerFactory.getLogger(CheckinService.class);

    /** UC307-3b 开映超时宽限（超过即转人工）：PRD 固定 30 分钟。 */
    static final Duration CHECKIN_GRACE = Duration.ofMinutes(30);

    private final TicketOrderRepository ticketOrderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatRepository seatRepository;
    private final EntityManager entityManager;

    public CheckinService(TicketOrderRepository ticketOrderRepository,
                          OrderItemRepository orderItemRepository,
                          SeatRepository seatRepository,
                          EntityManager entityManager) {
        this.ticketOrderRepository = ticketOrderRepository;
        this.orderItemRepository = orderItemRepository;
        this.seatRepository = seatRepository;
        this.entityManager = entityManager;
    }

    /** 座位视图（核销页展示场次座位）。 */
    public record SeatView(Long seatId, Integer rowNum, Integer colNum, SeatZone zone, BigDecimal price) {
    }

    /** 核销结果（含操作员，决策 2 口径）。 */
    public record CheckinOutcome(Long orderId, String orderNo, String pickupCode, String status,
                                 LocalDateTime checkinTime, String operator, Long showingId,
                                 String movieTitle, String hallName, LocalDateTime startTime,
                                 List<SeatView> seats) {
    }

    /**
     * 检票核销（UC307）。
     *
     * @param orderNo    订单号（与 pickupCode 至少其一）
     * @param pickupCode 取票码（8 位数字）
     * @param operator   核销操作员用户名（响应返回 + 应用日志记录，决策 2）
     * @throws BizException 10003 凭据缺失 / 12004 订单不存在 / 13008 已检票重复核销 /
     *                      13007 状态不可核销 / 13009 开映超 30 分钟转人工
     */
    @Transactional
    public CheckinOutcome checkin(String orderNo, String pickupCode, String operator) {
        if (!StringUtils.hasText(orderNo) && !StringUtils.hasText(pickupCode)) {
            throw new BizException(ErrorCode.INVALID_PARAM, "请提供订单号或取票码其一");
        }
        TicketOrder order = resolveOrder(orderNo, pickupCode);
        LocalDateTime now = LocalDateTime.now();

        // UC307-3a：状态校验——已检票重复核销幂等拒绝（13008），其余非已出票状态拒绝（13007）
        if (order.getStatus() == OrderState.CHECKED_IN) {
            throw new BizException(ErrorCode.CHECKIN_ALREADY_CHECKED_IN);
        }
        if (order.getStatus() != OrderState.ISSUED) {
            throw new BizException(ErrorCode.CHECKIN_STATUS_MISMATCH,
                    "订单当前状态不可核销（" + order.getStatus() + "），仅已出票订单可核销入场");
        }
        // UC307-3b：开映超 30 分钟 → 转人工（3b）
        Showing showing = order.getShowing();
        if (now.isAfter(showing.getStartTime().plus(CHECKIN_GRACE))) {
            throw new BizException(ErrorCode.CHECKIN_MANUAL_REQUIRED);
        }

        // UC307-5a / A8：条件更新原子迁移（WHERE status='已出票'）——并发核销仅一个生效
        int updated = entityManager.createQuery("""
                        UPDATE TicketOrder o
                           SET o.status = :toStatus, o.checkinTime = :checkinTime
                         WHERE o.id = :id AND o.status = :expectedStatus
                        """)
                .setParameter("toStatus", OrderState.CHECKED_IN)
                .setParameter("checkinTime", now)
                .setParameter("id", order.getId())
                .setParameter("expectedStatus", OrderState.ISSUED)
                .executeUpdate();
        if (updated == 0) {
            // 并发对手抢先核销或状态漂移：重读按实际状态拒绝（幂等：13008；状态不符：13007）
            entityManager.flush();
            entityManager.clear();
            TicketOrder fresh = ticketOrderRepository.findById(order.getId())
                    .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
            if (fresh.getStatus() == OrderState.CHECKED_IN) {
                throw new BizException(ErrorCode.CHECKIN_ALREADY_CHECKED_IN);
            }
            throw new BizException(ErrorCode.CHECKIN_STATUS_MISMATCH,
                    "订单当前状态不可核销（" + fresh.getStatus() + "），仅已出票订单可核销入场");
        }
        entityManager.flush();
        entityManager.clear();

        // 状态机口径核对：迁移后状态与 Issued.checkIn 的迁移结果一致（CHECKED_IN 终态）
        TicketOrder fresh = ticketOrderRepository.findById(order.getId())
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在"));
        if (fresh.statusHandler().stateEnum() != OrderState.CHECKED_IN) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "核销迁移后状态异常：" + fresh.getStatus());
        }

        Showing freshShowing = fresh.getShowing();
        Movie movie = freshShowing.getMovie();
        CinemaHall hall = freshShowing.getHall();
        List<SeatView> seats = orderItemRepository.findByOrderIdAndValidFlag(fresh.getId(), 1L).stream()
                .map(this::toSeatView)
                .toList();
        log.info("[UC307] 核销成功：operator={}，orderNo={}，pickupCode={}，orderId={}，场次 {}，checkinTime={}",
                operator, fresh.getOrderNo(), fresh.getPickupCode(), fresh.getId(),
                freshShowing.getId(), fresh.getCheckinTime());
        return new CheckinOutcome(fresh.getId(), fresh.getOrderNo(), fresh.getPickupCode(),
                fresh.getStatus().name(), fresh.getCheckinTime(), operator,
                freshShowing.getId(), movie.getTitle(), hall.getName(), freshShowing.getStartTime(), seats);
    }

    /** 凭据解析：均传时订单号优先（UC307-1"订单号或取票码"）；检索不到按 12004。 */
    private TicketOrder resolveOrder(String orderNo, String pickupCode) {
        if (StringUtils.hasText(orderNo)) {
            return ticketOrderRepository.findByOrderNo(orderNo.trim())
                    .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "订单不存在：" + orderNo));
        }
        return ticketOrderRepository.findByPickupCode(pickupCode.trim())
                .orElseThrow(() -> new BizException(ErrorCode.ORDER_NOT_FOUND, "取票码无效：" + pickupCode));
    }

    private SeatView toSeatView(OrderItem item) {
        Seat seat = item.getSeat();
        return new SeatView(seat.getId(), seat.getRowNum(), seat.getColNum(), seat.getZone(),
                item.getPriceSnapshot());
    }
}
