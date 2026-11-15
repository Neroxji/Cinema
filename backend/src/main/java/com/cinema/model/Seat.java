package com.cinema.model;

import com.cinema.model.enums.SeatState;
import com.cinema.model.enums.SeatZone;
import com.cinema.model.state.SeatStatus;
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
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * 场次座位实体（表 seat）：并发核心表，spec §1/§4。
 *
 * <p>并发防线（本卡落字段，行为逻辑属 task-03）：
 * <ul>
 *   <li>{@code @Version} 乐观锁：条件更新 {@code ... WHERE id=? AND status='空闲' AND version=?}</li>
 *   <li>唯一索引 uk_seat_showing_pos(showing_id, row_num, col_num)：同场次同位置唯一</li>
 *   <li>lock_expire_at / lock_user_id：锁定截止时间与锁定人（spec §4 已确认字段名）</li>
 * </ul>
 *
 * <p>// @Pattern: State(状态模式,Context角色=本实体，状态对象见 model/state), @UseCase: UC204/205/206
 */
@Entity
@Table(name = "seat")
public class Seat {

    /** 主键（自增）。多座位加锁按 seat.id 升序逐行执行，全系统统一顺序防死锁（spec §4）。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属场次。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showing_id", nullable = false)
    private Showing showing;

    /** 排号（1 起）。 */
    @Column(name = "row_num", nullable = false)
    private Integer rowNum;

    /** 座号（1 起）。 */
    @Column(name = "col_num", nullable = false)
    private Integer colNum;

    /** 分区：GOLDEN 黄金区 / NORMAL 普通区（由座位模板工厂写入）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "zone", length = 20, nullable = false)
    private SeatZone zone;

    /** 座位状态：FREE 空闲 / LOCKED 锁定 / SOLD 已售。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private SeatState status;

    /** 锁定截止时间（锁定态必填；超时由 @Scheduled 任务释放，task-03）。 */
    @Column(name = "lock_expire_at")
    private LocalDateTime lockExpireAt;

    /** 锁定人 user.id（不设外键：锁定为高频临时态，保持并发路径简单）。 */
    @Column(name = "lock_user_id")
    private Long lockUserId;

    /** 乐观锁版本号（@Version；插入 0，每次条件更新 +1）。 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Showing getShowing() {
        return showing;
    }

    public void setShowing(Showing showing) {
        this.showing = showing;
    }

    public Integer getRowNum() {
        return rowNum;
    }

    public void setRowNum(Integer rowNum) {
        this.rowNum = rowNum;
    }

    public Integer getColNum() {
        return colNum;
    }

    public void setColNum(Integer colNum) {
        this.colNum = colNum;
    }

    public SeatZone getZone() {
        return zone;
    }

    public void setZone(SeatZone zone) {
        this.zone = zone;
    }

    public SeatState getStatus() {
        return status;
    }

    public void setStatus(SeatState status) {
        this.status = status;
    }

    public LocalDateTime getLockExpireAt() {
        return lockExpireAt;
    }

    public void setLockExpireAt(LocalDateTime lockExpireAt) {
        this.lockExpireAt = lockExpireAt;
    }

    public Long getLockUserId() {
        return lockUserId;
    }

    public void setLockUserId(Long lockUserId) {
        this.lockUserId = lockUserId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    // ===================== task-03 追加：State 模式委托（transient 行为，不参与持久化映射） =====================

    /**
     * 状态处理器（Context 角色入口）：按当前 status 分派到 model/state 下对应状态对象。
     * 一切状态迁移必须经状态对象校验合法性，非法迁移抛 IllegalStateTransitionException（spec §3.2）。
     */
    public SeatStatus statusHandler() {
        return SeatStatus.of(status);
    }

    /** 选座锁定（UC204-4 选座 / UC305 候补递补——同一 lock 事件）：写锁定人与截止时间，version+1。 */
    public void lock(Long userId, LocalDateTime lockExpireAt) {
        statusHandler().lock(this, userId, lockExpireAt);
    }

    /** 支付出票（UC205-5，task-04 调用）：锁定→已售并清空锁定字段（明细快照由出票服务生成）。 */
    public void sell() {
        statusHandler().sell(this);
    }

    /** 锁定超时释放（UC206）：释放前复查（仍锁定且已过期）后回空闲并清空锁定字段。 */
    public void releaseOnTimeout(LocalDateTime now) {
        statusHandler().releaseOnTimeout(this, now);
    }

    /** 观众主动放弃（UC204）：复查仍被本人锁定后回空闲并清空锁定字段。 */
    public void releaseByAbandon(Long userId) {
        statusHandler().releaseByAbandon(this, userId);
    }

    /** 退票释放（UC302-5，task-05 调用）：已售→空闲（广播释放事件/候补检查由服务层编排）。 */
    public void releaseByRefund() {
        statusHandler().releaseByRefund(this);
    }

    /** 改签释放（UC303，task-05 调用）：已售→空闲，原场次座位释放后新场次重新走锁座流程。 */
    public void releaseByChange() {
        statusHandler().releaseByChange(this);
    }
}
