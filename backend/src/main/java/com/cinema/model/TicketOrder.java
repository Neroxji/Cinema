package com.cinema.model;

import com.cinema.model.enums.OrderState;
import com.cinema.model.state.OrderStatus;
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
 *
 * <p>// @Pattern: State(状态模式,Context角色=本实体，状态对象见 model/state), @UseCase: UC205/302/307
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

    // ===================== task-03 追加：State 模式委托（transient 行为，不参与持久化映射） =====================

    /**
     * 状态处理器（Context 角色入口）：按当前 status 分派到 model/state 下对应状态对象。
     * 一切状态迁移必须经状态对象校验合法性，非法迁移抛 IllegalStateTransitionException（spec §3.1 行8）。
     */
    public OrderStatus statusHandler() {
        return OrderStatus.of(status);
    }

    /** 支付成功（UC205，task-04 调用）：待支付→已出票并记录支付时间；座位→已售/广播出票事件由服务层编排。 */
    public void pay(LocalDateTime payTime) {
        statusHandler().pay(this, payTime);
    }

    /** 支付失败（UC205-3a）：待支付自环保持不变（座位维持锁定至倒计时结束）。 */
    public void payFailed() {
        statusHandler().payFailed(this);
    }

    /** 锁定超时（UC206）/观众主动放弃（UC204）：待支付→已取消；座位→空闲/广播释放事件由服务层编排。 */
    public void cancel() {
        statusHandler().cancel(this);
    }

    /** 检票核销（UC307，task-05 调用）：已出票→已检票（终态）并记录核销时间；操作员记录由核销服务承载。 */
    public void checkIn(LocalDateTime checkinTime) {
        statusHandler().checkIn(this, checkinTime);
    }

    /** 退票（UC302，task-05 调用）：已出票→已退票（终态）；座位→空闲/退款/广播释放事件由服务层编排。 */
    public void refund() {
        statusHandler().refund(this);
    }

    /** 改签（UC303，task-05 调用）：已出票自迁移（指向新场次），留存改签来源；每单限 1 次校验属 UC303 业务规则。 */
    public void changeShowing(Long newShowingId) {
        statusHandler().changeShowing(this, newShowingId);
    }
}
