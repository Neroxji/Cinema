package com.cinema.service.aftermarket;

import com.cinema.model.CinemaHall;
import com.cinema.model.Movie;
import com.cinema.model.OrderItem;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.TicketOrder;
import com.cinema.model.enums.SeatZone;
import com.cinema.repository.OrderItemRepository;
import com.cinema.repository.TicketOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 我的订单查询服务（UC301，task-05）。
 *
 * <p>// @UseCase: UC301(查看我的订单列表——本人数据，含状态/场次/座位/金额/取票码)
 *
 * <p>展示口径：订单按 id 降序（新单在前）；座位明细取订单<b>全部</b>明细（含已失效），
 * valid 标志区分有效票与已释放票（退票/改签后历史明细仍可见，UC301 列表页直读）；
 * 取票码仅已出票及之后状态非空（待支付订单尚无码）。
 */
// @UseCase: UC301
@Service
public class OrderQueryService {

    private final TicketOrderRepository ticketOrderRepository;
    private final OrderItemRepository orderItemRepository;

    public OrderQueryService(TicketOrderRepository ticketOrderRepository,
                             OrderItemRepository orderItemRepository) {
        this.ticketOrderRepository = ticketOrderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    /** 场次概要视图。 */
    public record ShowingView(Long showingId, String movieTitle, String hallName, LocalDateTime startTime) {
    }

    /** 座位明细视图（valid=false 表示该明细已因退票/改签失效）。 */
    public record SeatItemView(Long seatId, Integer rowNum, Integer colNum, SeatZone zone,
                               BigDecimal price, boolean valid) {
    }

    /** 订单列表元素。 */
    public record OrderSummary(Long orderId, String orderNo, String status, BigDecimal totalAmount,
                               String pickupCode, LocalDateTime payTime, LocalDateTime checkinTime,
                               Long changeFromShowingId, ShowingView showing, List<SeatItemView> seats) {
    }

    /** 我的订单列表（UC301）：本人数据 + 场次/座位/金额/取票码。 */
    @Transactional(readOnly = true)
    public List<OrderSummary> listMyOrders(Long userId) {
        List<TicketOrder> orders = ticketOrderRepository.findByUserIdOrderByIdDesc(userId);
        return orders.stream().map(this::toSummary).toList();
    }

    private OrderSummary toSummary(TicketOrder order) {
        Showing showing = order.getShowing();
        Movie movie = showing.getMovie();
        CinemaHall hall = showing.getHall();
        List<SeatItemView> seats = orderItemRepository.findByOrderId(order.getId()).stream()
                .sorted(Comparator.comparing(item -> item.getSeat().getId()))
                .map(this::toSeatView)
                .toList();
        return new OrderSummary(order.getId(), order.getOrderNo(), order.getStatus().name(),
                order.getTotalAmount(), order.getPickupCode(), order.getPayTime(), order.getCheckinTime(),
                order.getChangeFromShowingId(),
                new ShowingView(showing.getId(), movie.getTitle(), hall.getName(), showing.getStartTime()),
                seats);
    }

    private SeatItemView toSeatView(OrderItem item) {
        Seat seat = item.getSeat();
        return new SeatItemView(seat.getId(), seat.getRowNum(), seat.getColNum(), seat.getZone(),
                item.getPriceSnapshot(), Long.valueOf(1L).equals(item.getValidFlag()));
    }
}
