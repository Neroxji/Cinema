package com.cinema.model.state;

import com.cinema.model.Seat;
import com.cinema.model.enums.SeatState;

import java.time.LocalDateTime;

/**
 * 空闲状态（座位生命周期起点，spec §3.2 行 1~2：可选座/可被候补递补锁定）。
 *
 * <p>合法事件：仅 lock（观众选座 UC204 / 候补递补 UC305——同一事件方法）；
 * 出票、各类释放对空闲座位一律拒绝。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC204(选座)/305(候补递补)
 */
public final class SeatFree implements SeatStatus {

    /** 无状态单例。 */
    private static final SeatFree INSTANCE = new SeatFree();

    private SeatFree() {
    }

    /** 取本状态单例。 */
    public static SeatFree instance() {
        return INSTANCE;
    }

    @Override
    public SeatState stateEnum() {
        return SeatState.FREE;
    }

    /** 行1/2：空闲--选座/候补递补-->锁定；动作=写锁定人/截止时间、version+1（DB 路径由条件更新 SQL 等价承载）。 */
    @Override
    public void lock(Seat seat, Long userId, LocalDateTime lockExpireAt) {
        if (userId == null || lockExpireAt == null) {
            throw new IllegalStateTransitionException("锁定座位必须指定锁定人与截止时间");
        }
        seat.setStatus(SeatState.LOCKED);
        seat.setLockUserId(userId);
        seat.setLockExpireAt(lockExpireAt);
        SeatStatus.bumpVersion(seat);
    }

    /** 空闲座位不可出票（出票仅适用于锁定态，UC205-4 复查的前置）。 */
    @Override
    public void sell(Seat seat) {
        throw reject(seat, "空闲座位不可出票");
    }

    /** 空闲座位无锁可超释，拒绝。 */
    @Override
    public void releaseOnTimeout(Seat seat, LocalDateTime now) {
        throw reject(seat, "空闲座位不适用锁定超时释放");
    }

    /** 空闲座位无锁可放弃，拒绝。 */
    @Override
    public void releaseByAbandon(Seat seat, Long userId) {
        throw reject(seat, "空闲座位不适用主动放弃释放");
    }

    /** 空闲座位无票可退，拒绝。 */
    @Override
    public void releaseByRefund(Seat seat) {
        throw reject(seat, "空闲座位不适用退票释放");
    }

    /** 空闲座位无票可改签，拒绝。 */
    @Override
    public void releaseByChange(Seat seat) {
        throw reject(seat, "空闲座位不适用改签释放");
    }

    /** 构造"非法迁移拒绝"异常（携带座位与当前状态，便于定位）。 */
    private IllegalStateTransitionException reject(Seat seat, String why) {
        return new IllegalStateTransitionException(
                "座位" + String.valueOf(seat.getId()) + "当前状态[" + stateEnum() + "]拒绝迁移：" + why);
    }
}
