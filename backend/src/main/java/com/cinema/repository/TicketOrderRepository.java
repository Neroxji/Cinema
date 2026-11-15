package com.cinema.repository;

import com.cinema.model.TicketOrder;
import org.springframework.data.jpa.repository.JpaRepository;

/** 订单仓库（表 ticket_order）。 */
public interface TicketOrderRepository extends JpaRepository<TicketOrder, Long> {
}
