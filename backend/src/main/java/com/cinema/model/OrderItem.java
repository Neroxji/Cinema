package com.cinema.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * 订单明细实体（表 order_item）：一张票一条明细，"防双卖唯一索引"载体，spec §1/§4。
 *
 * <p>唯一索引 uk_order_item_seat_valid(seat_id, valid_flag)：
 * 同一座位至多一条有效明细；退票/失效时 valid_flag 置 NULL（NULL 不参与唯一性比对），
 * 座位可重售——"防双卖"与"退后重售"并存（spec §4 已确认兼容方案 A）。
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属订单。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private TicketOrder order;

    /** 票对应座位（有效明细与座位 1:1）。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    /** 出票时快照价（策略切换/改价不影响已出票订单，UC105 后置条件）。 */
    @Column(name = "price_snapshot", nullable = false, precision = 10, scale = 2)
    private BigDecimal priceSnapshot;

    /** 有效标志：1=有效；退票/失效置 NULL（绕开唯一索引，允许座位重售）。 */
    @Column(name = "valid_flag")
    private Long validFlag;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TicketOrder getOrder() {
        return order;
    }

    public void setOrder(TicketOrder order) {
        this.order = order;
    }

    public Seat getSeat() {
        return seat;
    }

    public void setSeat(Seat seat) {
        this.seat = seat;
    }

    public BigDecimal getPriceSnapshot() {
        return priceSnapshot;
    }

    public void setPriceSnapshot(BigDecimal priceSnapshot) {
        this.priceSnapshot = priceSnapshot;
    }

    public Long getValidFlag() {
        return validFlag;
    }

    public void setValidFlag(Long validFlag) {
        this.validFlag = validFlag;
    }
}
