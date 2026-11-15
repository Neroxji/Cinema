package com.cinema.model.state;

import com.cinema.model.Seat;
import com.cinema.model.enums.SeatState;

import java.time.LocalDateTime;

/**
 * 座位状态机行为接口（State 模式 · State 角色，spec §3.2/§5）。
 *
 * <p>// @Pattern: State(State角色), @UseCase: UC204/205/206
 *
 * <p>每个座位状态对应一个实现类（SeatFree/SeatLocked/SeatSold），Context = Seat：
 * 实体经 {@code statusHandler()} 按当前 status 分派，非法迁移抛 {@link IllegalStateTransitionException}
 * （非空闲不可再锁、锁定未过期不可超时释放等，UC204-4a 的状态机层守卫）。
 *
 * <p>version 约定（task-03 决策）：状态对象的迁移动作显式 {@code version+1}，镜像 spec §3.2
 * "条件更新+乐观锁…version+1"——供纯逻辑路径使用（单测 U3；后续任务经实体保存的迁移在
 * flush 时 @Version 会再加 1，单调递增不影响乐观锁正确性）；SeatLockService 的 DB 条件更新
 * 路径在 SQL 内 {@code version=version+1}（见 SeatRepository 注释），两条路径二选一执行，
 * 不对同一行重复生效。
 */
public interface SeatStatus {

    /** 本状态对应的持久化枚举值（seat.status 存储值）。 */
    SeatState stateEnum();

    /** 选座锁定（UC204-4 观众选座 / UC305 候补递补成功——同一 lock 事件）：空闲 → 锁定。 */
    void lock(Seat seat, Long userId, LocalDateTime lockExpireAt);

    /** 支付出票（UC205-5）：锁定 → 已售（订单明细快照由 task-04 生成）。 */
    void sell(Seat seat);

    /**
     * 锁定超时释放（UC206）：锁定 → 空闲。
     * 释放前复查语义：仅当 status=锁定 且 lock_expire_at 已过期才允许释放（spec §3.2 锁定行）。
     */
    void releaseOnTimeout(Seat seat, LocalDateTime now);

    /** 观众主动放弃（UC204）：锁定 → 空闲；复查语义：仅锁定人本人可放弃。 */
    void releaseByAbandon(Seat seat, Long userId);

    /** 退票释放（UC302-5）：已售 → 空闲（广播释放事件/候补检查由 task-05 编排）。 */
    void releaseByRefund(Seat seat);

    /** 改签释放（UC303）：已售 → 空闲，原场次座位释放后新场次重新走锁座流程。 */
    void releaseByChange(Seat seat);

    /** 按持久化枚举分派到对应状态对象（实现均为无状态单例）。 */
    static SeatStatus of(SeatState state) {
        if (state == null) {
            throw new IllegalStateTransitionException("座位状态缺失，无法分派状态对象");
        }
        return switch (state) {
            case FREE -> SeatFree.instance();
            case LOCKED -> SeatLocked.instance();
            case SOLD -> SeatSold.instance();
        };
    }

    /** 迁移动作共享：版本自增（version 为空按 0 起算，镜像条件更新 SQL 的 version=version+1）。 */
    static void bumpVersion(Seat seat) {
        long current = seat.getVersion() == null ? 0L : seat.getVersion();
        seat.setVersion(current + 1);
    }

    /** 迁移动作共享：统一释放——→ 空闲、清空锁定字段、version+1（spec §3.2 释放三行的公共动作）。 */
    static void releaseToFree(Seat seat) {
        seat.setStatus(SeatState.FREE);
        seat.setLockUserId(null);
        seat.setLockExpireAt(null);
        bumpVersion(seat);
    }
}
