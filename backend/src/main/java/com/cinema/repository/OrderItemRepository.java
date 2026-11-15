package com.cinema.repository;

import com.cinema.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 订单明细仓库（表 order_item）。
 *
 * <p>task-05 追加（追加式，不改既有方法）：退票/改签按"有效明细"读取（valid_flag=1，
 * 防双卖唯一索引载体，spec §4.4），以及撤销命令的座位重售守卫。
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 查询订单全部明细（含已失效，订单列表展示用）。 */
    List<OrderItem> findByOrderId(Long orderId);

    /** 查询订单有效明细（valid_flag=1：退票/改签释放座位与重算金额均以此为口径）。 */
    List<OrderItem> findByOrderIdAndValidFlag(Long orderId, Long validFlag);

    /** 查询订单已失效明细（valid_flag IS NULL：撤销退票命令恢复有效标志用）。 */
    List<OrderItem> findByOrderIdAndValidFlagIsNull(Long orderId);

    /** 座位是否存在指定有效标志的明细（撤销退票前守卫：座位未被他人重新售出）。 */
    boolean existsBySeatIdAndValidFlag(Long seatId, Long validFlag);
}
