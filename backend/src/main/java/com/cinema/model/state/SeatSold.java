package com.cinema.model.state;

import com.cinema.model.Seat;
import com.cinema.model.enums.SeatState;

import java.time.LocalDateTime;

/**
 * 已售状态（已出票，spec §3.2 行 6~7：退票/改签释放后座位可重售——防双卖唯一索引在
 * order_item 层兜底，退票置 valid_flag=NULL 绕开唯一性比对，spec §4.4）。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC302(退票)/303(改签)
 */
public final class SeatSold implements SeatStatus {

    /** 无状态单例。 */
    private static final SeatSold INSTANCE = new SeatSold();

    private SeatSold() {
    }

    /** 取本状态单例。 */
    public static SeatSold instance() {
        return INSTANCE;
    }

    @Override
    public SeatState stateEnum() {
        return SeatState.SOLD;
    }

    /** 已售座位不可再锁定（防超卖的状态机层守卫）。 */
    @Override
    public void lock(Seat seat, Long userId, LocalDateTime lockExpireAt) {
        throw reject(seat, "座位已售出，不可锁定");
    }

    /** 已售座位不可重复出票。 */
    @Override
    public void sell(Seat seat) {
        throw reject(seat, "座位已售出，不可重复出票");
    }

    /** 已售座位不适用锁定超时释放，拒绝。 */
    @Override
    public void releaseOnTimeout(Seat seat, LocalDateTime now) {
        throw reject(seat, "已售座位不适用锁定超时释放");
    }

    /** 已售座位不适用主动放弃释放，拒绝。 */
    @Override
    public void releaseByAbandon(Seat seat, Long userId) {
        throw reject(seat, "已售座位不适用主动放弃释放");
    }

    /** 行6：已售--退票-->空闲；动作=清空锁定字段、version+1（广播释放事件→候补检查由 task-05 编排）。 */
    @Override
    public void releaseByRefund(Seat seat) {
        SeatStatus.releaseToFree(seat);
    }

    /** 行7：已售--改签-->空闲；动作同退票释放（原场次座位释放，新场次重新走锁座流程）。 */
    @Override
    public void releaseByChange(Seat seat) {
        SeatStatus.releaseToFree(seat);
    }

    /** 构造"非法迁移拒绝"异常（携带座位与当前状态，便于定位）。 */
    private IllegalStateTransitionException reject(Seat seat, String why) {
        return new IllegalStateTransitionException(
                "座位" + String.valueOf(seat.getId()) + "当前状态[" + stateEnum() + "]拒绝迁移：" + why);
    }
}
