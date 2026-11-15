package com.cinema.repository;

import com.cinema.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 订单明细仓库（表 order_item）。 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 查询订单全部明细。 */
    List<OrderItem> findByOrderId(Long orderId);
}
