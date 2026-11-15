package com.cinema.model;

import com.cinema.model.enums.OrderState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购票订单实体（表 ticket_order），spec §1；N:1 user、showing；1:N order_item。
 *
 * <p>状态机（5 态，spec §3.1）与改签/退票/核销行为由 task-03/05 实现；
 * 改签历史由 change_from_showing_id 字段承载（spec 已确认砍掉备忘录模式）。
 */
@Entity
@Table(name = "ticket_order")
public class TicketOrder {

    /** 主键（自增）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单号（唯一索引 uk_ticket_order_no；检票核销可用订单号检索，UC307）。 */
    @Column(name = "order_no", length = 32, nullable = false)
    private String orderNo;

    /**
     * 取票码（spec §1：8 位数字，出票时生成；待支付订单尚无码故可 NULL）。
     * 全库唯一索引 uk_ticket_order_pickup_code（唯一索引对多行 NULL 行为：MySQL 与 H2 一致，NULL 不参与比对）。
     * 检票核销可凭取票码或订单号检索（@UseCase: UC307）。出票时的码生成逻辑属 task-04。
     */
    @Column(name = "pickup_code", length = 8)
    private String pickupCode;

    /** 下单用户。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 所属场次（改签后指向新场次）。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showing_id", nullable = false)
    private Showing showing;

    /** 订单状态：PENDING_PAYMENT / ISSUED / CHECKED_IN / REFUNDED / CANCELED。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OrderState status;

    /** 订单总额 = 有效明细 price_snapshot 之和。 */
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /** 支付时间（出票时间）。 */
    @Column(name = "pay_time")
    private LocalDateTime payTime;

    /** 检票核销时间（UC307，幂等记录）。 */
    @Column(name = "checkin_time")
    private LocalDateTime checkinTime;

    /**
     * 改签来源场次 id（可空；仅改签单填写，承载改签历史，spec §3.1/§5）。
     * 不设外键：保留来源场次被取消后的历史可读性，如需强一致可后续补。
     */
    @Column(name = "change_from_showing_id")
    private Long changeFromShowingId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getPickupCode() {
        return pickupCode;
    }

    public void setPickupCode(String pickupCode) {
        this.pickupCode = pickupCode;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Showing getShowing() {
        return showing;
    }

    public void setShowing(Showing showing) {
        this.showing = showing;
    }

    public OrderState getStatus() {
        return status;
    }

    public void setStatus(OrderState status) {
        this.status = status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public LocalDateTime getPayTime() {
        return payTime;
    }

    public void setPayTime(LocalDateTime payTime) {
        this.payTime = payTime;
    }

    public LocalDateTime getCheckinTime() {
        return checkinTime;
    }

    public void setCheckinTime(LocalDateTime checkinTime) {
        this.checkinTime = checkinTime;
    }

    public Long getChangeFromShowingId() {
        return changeFromShowingId;
    }

    public void setChangeFromShowingId(Long changeFromShowingId) {
        this.changeFromShowingId = changeFromShowingId;
    }
}
