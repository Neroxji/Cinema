package com.cinema.model.state;

import com.cinema.model.Seat;
import com.cinema.model.enums.SeatState;

import java.time.LocalDateTime;

/**
 * 锁定状态（限时锁定中，spec §3.2 行 3~5：可出票；超时/放弃经"释放前复查"回空闲）。
 *
 * <p>释放前复查语义（spec §3.2 锁定行）：超时释放须 status=锁定 且 lock_expire_at 已过期；
 * 主动放弃须仍被本人锁定——复查不通过即抛异常拒绝，座位保持锁定（DB 路径的原子化实现见
 * SeatRepository.conditionalReleaseIfExpiredBefore 的 WHERE 条件）。
 *
 * <p>// @Pattern: State(ConcreteState角色), @UseCase: UC205(出票)/206(超时)/204(放弃)
 */
public final class SeatLocked implements SeatStatus {

    /** 无状态单例。 */
    private static final SeatLocked INSTANCE = new SeatLocked();

    private SeatLocked() {
    }

    /** 取本状态单例。 */
    public static SeatLocked instance() {
        return INSTANCE;
    }

    @Override
    public SeatState stateEnum() {
        return SeatState.LOCKED;
    }

    /** 锁定态不可重复锁定（已被占用，UC204-4a 状态机层守卫）。 */
    @Override
    public void lock(Seat seat, Long userId, LocalDateTime lockExpireAt) {
        throw reject(seat, "座位已被锁定，不可重复锁定");
    }

    /** 行3：锁定--支付出票-->已售；动作=清空锁定字段（终止倒计时）、version+1（明细快照由 task-04 生成）。 */
    @Override
    public void sell(Seat seat) {
        seat.setStatus(SeatState.SOLD);
        seat.setLockUserId(null);
        seat.setLockExpireAt(null);
        SeatStatus.bumpVersion(seat);
    }

    /**
     * 行4：锁定--锁定超时-->空闲；释放前复查：仍锁定且 lock_expire_at 已过期方可释放，
     * 未过期拒绝（防误放他人有效锁定）；动作=清空锁定字段、version+1。
     */
    @Override
    public void releaseOnTimeout(Seat seat, LocalDateTime now) {
        LocalDateTime expireAt = seat.getLockExpireAt();
        if (expireAt == null || !expireAt.isBefore(now)) {
            throw reject(seat, "释放前复查不通过：锁定尚未过期，不可超时释放");
        }
        SeatStatus.releaseToFree(seat);
    }

    /** 行5：锁定--观众主动放弃-->空闲；复查：仅锁定人本人可放弃，动作同上。 */
    @Override
    public void releaseByAbandon(Seat seat, Long userId) {
        if (userId == null || !userId.equals(seat.getLockUserId())) {
            throw reject(seat, "释放前复查不通过：仅锁定人本人可放弃该锁定");
        }
        SeatStatus.releaseToFree(seat);
    }

    /** 退票释放仅适用于已售态（锁定无票可退），拒绝。 */
    @Override
    public void releaseByRefund(Seat seat) {
        throw reject(seat, "锁定座位不适用退票释放");
    }

    /** 改签释放仅适用于已售态，拒绝。 */
    @Override
    public void releaseByChange(Seat seat) {
        throw reject(seat, "锁定座位不适用改签释放");
    }

    /** 构造"非法迁移拒绝"异常（携带座位与当前状态，便于定位）。 */
    private IllegalStateTransitionException reject(Seat seat, String why) {
        return new IllegalStateTransitionException(
                "座位" + String.valueOf(seat.getId()) + "当前状态[" + stateEnum() + "]拒绝迁移：" + why);
    }
}
