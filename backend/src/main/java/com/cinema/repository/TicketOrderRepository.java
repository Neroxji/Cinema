package com.cinema.repository;

import com.cinema.model.TicketOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 订单仓库（表 ticket_order）。
 *
 * <p>task-05 追加（追加式，不改既有方法）：我的订单列表（UC301）与检票核销检索（UC307）。
 */
public interface TicketOrderRepository extends JpaRepository<TicketOrder, Long> {

    /** 我的订单列表（UC301）：按订单 id 降序（新单在前）。 */
    List<TicketOrder> findByUserIdOrderByIdDesc(Long userId);

    /** 凭订单号检索（UC307 检票核销凭据之一）。 */
    Optional<TicketOrder> findByOrderNo(String orderNo);

    /** 凭取票码检索（UC307 检票核销凭据之一；8 位数字全库唯一）。 */
    Optional<TicketOrder> findByPickupCode(String pickupCode);
}
