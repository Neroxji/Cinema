package com.cinema.model.state;

import com.cinema.model.Seat;
import com.cinema.model.enums.SeatState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * U3 · 座位状态机单元测试（纯逻辑，Context 用新建实例——测试计划 §2 U3，P0 护栏）。
 *
 * <p>覆盖 spec §3.2 座位状态转移表：
 * <ul>
 *   <li>全链：空闲→锁定→已售→退票→空闲，每步状态、锁定字段与 version 变化正确</li>
 *   <li>释放前复查语义（锁定行）：超时释放须"仍锁定且已过期"；主动放弃须"仍被本人锁定"</li>
 *   <li>非法迁移拒绝（非空闲不可再锁、空闲/已售不可出票等，UC204-4a 的状态机层守卫）</li>
 * </ul>
 *
 * <p>Context = {@link Seat}（task-03 追加的状态委托方法）。version 递增由状态动作显式 +1，
 * 与 DB 条件更新 SQL 的 {@code version=version+1} 互为镜像（两条路径二选一，见 SeatRepository 注释）。
 */
@DisplayName("U3 · 座位状态机（spec §3.2，State 模式 Context=Seat）")
class SeatStatusStateMachineTest {

    /** 锁定截止时间（未来 30 秒）。 */
    private static final LocalDateTime LOCK_UNTIL = LocalDateTime.now().plusSeconds(30);

    /** 构造指定状态、版本 0 的座位（纯逻辑新建实例，不入库）。 */
    private Seat seatOf(SeatState state) {
        Seat seat = new Seat();
        seat.setStatus(state);
        seat.setVersion(0L);
        return seat;
    }

    // =====================================================================
    // U3-1 全链：空闲→锁定→已售→退→空闲（每步状态与 version 变化正确）
    // =====================================================================

    @Test
    @DisplayName("U3-1 全链 空闲→锁定→已售→退票→空闲：状态、锁定字段、version 逐步正确")
    void u3_fullChain_freeLockSoldRefundFree() {
        Seat seat = seatOf(SeatState.FREE);

        // 步骤1：观众选座锁定（UC204-4；UC305 候补递补为同一 lock 事件）
        seat.lock(7L, LOCK_UNTIL);
        assertEquals(SeatState.LOCKED, seat.getStatus(), "锁定后应为锁定态");
        assertEquals(7L, seat.getLockUserId(), "应写入锁定人");
        assertEquals(LOCK_UNTIL, seat.getLockExpireAt(), "应写入锁定截止时间");
        assertEquals(1L, seat.getVersion(), "锁定迁移应 version+1");

        // 步骤2：支付出票（UC205-5）
        seat.sell();
        assertEquals(SeatState.SOLD, seat.getStatus(), "出票后应为已售");
        assertNull(seat.getLockUserId(), "已售后应清空锁定人（倒计时终止）");
        assertNull(seat.getLockExpireAt(), "已售后应清空锁定截止时间");
        assertEquals(2L, seat.getVersion(), "出票迁移应再 version+1");

        // 步骤3：退票释放（UC302-5）
        seat.releaseByRefund();
        assertEquals(SeatState.FREE, seat.getStatus(), "退票后应回空闲");
        assertNull(seat.getLockUserId(), "释放应清空锁定人");
        assertNull(seat.getLockExpireAt(), "释放应清空锁定截止时间");
        assertEquals(3L, seat.getVersion(), "退票释放应再 version+1");

        // 步骤4：释放后可再次锁定（防双卖与退后重售并存的状态机基础）
        seat.lock(8L, LOCK_UNTIL.plusSeconds(30));
        assertEquals(SeatState.LOCKED, seat.getStatus());
        assertEquals(8L, seat.getLockUserId());
        assertEquals(4L, seat.getVersion(), "重锁应再 version+1");
    }

    // =====================================================================
    // U3-2 释放前复查语义（spec §3.2 锁定行：复查 status=锁定 且已过期）
    // =====================================================================

    @Test
    @DisplayName("U3-2 已过期锁定座位可超时释放：回空闲并清空锁定字段（UC206）")
    void u3_timeoutRelease_afterExpire_releases() {
        Seat seat = seatOf(SeatState.LOCKED);
        seat.setLockUserId(7L);
        LocalDateTime expiredAt = LocalDateTime.now().minusSeconds(30);
        seat.setLockExpireAt(expiredAt);

        seat.releaseOnTimeout(LocalDateTime.now());
        assertEquals(SeatState.FREE, seat.getStatus(), "超时释放应回空闲");
        assertNull(seat.getLockUserId(), "超时释放应清空锁定人");
        assertNull(seat.getLockExpireAt(), "超时释放应清空截止时间");
        assertEquals(1L, seat.getVersion(), "超时释放应 version+1（条件更新释放）");
    }

    @Test
    @DisplayName("U3-3 释放前复查：锁定未过期时超时释放必须拒绝，座位保持锁定")
    void u3_timeoutRelease_beforeExpire_rejected() {
        Seat seat = seatOf(SeatState.LOCKED);
        seat.setLockUserId(7L);
        seat.setLockExpireAt(LOCK_UNTIL);

        assertThrows(IllegalStateTransitionException.class,
                () -> seat.releaseOnTimeout(LocalDateTime.now()), "尚未过期不可超时释放");
        assertEquals(SeatState.LOCKED, seat.getStatus(), "复查拒绝后座位必须保持锁定");
        assertEquals(7L, seat.getLockUserId(), "复查拒绝后锁定人不变");
        assertEquals(0L, seat.getVersion(), "拒绝迁移不得动版本号");
    }

    @Test
    @DisplayName("U3-4 空闲/已售座位收到超时释放事件：拒绝")
    void u3_timeoutRelease_whenNotLocked_rejected() {
        Seat freeSeat = seatOf(SeatState.FREE);
        assertThrows(IllegalStateTransitionException.class,
                () -> freeSeat.releaseOnTimeout(LocalDateTime.now()));

        Seat soldSeat = seatOf(SeatState.SOLD);
        soldSeat.setVersion(5L);
        assertThrows(IllegalStateTransitionException.class,
                () -> soldSeat.releaseOnTimeout(LocalDateTime.now()));
        assertEquals(SeatState.SOLD, soldSeat.getStatus());
        assertEquals(5L, soldSeat.getVersion());
    }

    // =====================================================================
    // U3-3 观众主动放弃（UC204：复查仍被本人锁定方可释放）
    // =====================================================================

    @Test
    @DisplayName("U3-5 锁定人本人主动放弃：回空闲并清空锁定字段")
    void u3_abandon_byLocker_releases() {
        Seat seat = seatOf(SeatState.LOCKED);
        seat.setLockUserId(7L);
        seat.setLockExpireAt(LOCK_UNTIL);

        seat.releaseByAbandon(7L);
        assertEquals(SeatState.FREE, seat.getStatus());
        assertNull(seat.getLockUserId());
        assertNull(seat.getLockExpireAt());
        assertEquals(1L, seat.getVersion());
    }

    @Test
    @DisplayName("U3-6 非锁定人主动放弃：拒绝（不可释放他人锁定）")
    void u3_abandon_byOther_rejected() {
        Seat seat = seatOf(SeatState.LOCKED);
        seat.setLockUserId(7L);
        seat.setLockExpireAt(LOCK_UNTIL);

        assertThrows(IllegalStateTransitionException.class,
                () -> seat.releaseByAbandon(8L), "他人不可放弃别人的锁定");
        assertEquals(SeatState.LOCKED, seat.getStatus(), "拒绝后座位保持锁定");
        assertEquals(7L, seat.getLockUserId());
        assertEquals(0L, seat.getVersion());
    }

    // =====================================================================
    // U3-4 非法迁移拒绝（UC204-4a 的状态机层守卫）
    // =====================================================================

    @Test
    @DisplayName("U3-7 锁定/已售座位不可再次锁定：拒绝")
    void u3_lock_whenNotFree_rejected() {
        Seat locked = seatOf(SeatState.LOCKED);
        locked.setLockUserId(7L);
        locked.setLockExpireAt(LOCK_UNTIL);
        assertThrows(IllegalStateTransitionException.class, () -> locked.lock(8L, LOCK_UNTIL));
        assertEquals(7L, locked.getLockUserId(), "拒绝后锁定人不得被覆盖");

        Seat sold = seatOf(SeatState.SOLD);
        assertThrows(IllegalStateTransitionException.class, () -> sold.lock(8L, LOCK_UNTIL));
        assertEquals(SeatState.SOLD, sold.getStatus());
    }

    @Test
    @DisplayName("U3-8 空闲/已售座位不可出票：拒绝（出票仅适用于锁定态）")
    void u3_sell_whenNotLocked_rejected() {
        Seat free = seatOf(SeatState.FREE);
        assertThrows(IllegalStateTransitionException.class, free::sell);
        assertEquals(SeatState.FREE, free.getStatus());

        Seat sold = seatOf(SeatState.SOLD);
        assertThrows(IllegalStateTransitionException.class, sold::sell);
        assertEquals(SeatState.SOLD, sold.getStatus());
    }

    @Test
    @DisplayName("U3-9 退票释放仅适用于已售：锁定/空闲座位收到退票事件拒绝")
    void u3_refundRelease_whenNotSold_rejected() {
        Seat locked = seatOf(SeatState.LOCKED);
        locked.setLockUserId(7L);
        locked.setLockExpireAt(LOCK_UNTIL);
        assertThrows(IllegalStateTransitionException.class, locked::releaseByRefund);
        assertEquals(SeatState.LOCKED, locked.getStatus());

        Seat free = seatOf(SeatState.FREE);
        assertThrows(IllegalStateTransitionException.class, free::releaseByRefund);
        assertEquals(SeatState.FREE, free.getStatus());
    }

    @Test
    @DisplayName("U3-10 改签释放仅适用于已售：已售改签回空闲并 version+1（UC303）")
    void u3_changeRelease_onlyFromSold() {
        Seat sold = seatOf(SeatState.SOLD);
        sold.releaseByChange();
        assertEquals(SeatState.FREE, sold.getStatus(), "改签应释放原场次座位");
        assertNull(sold.getLockUserId());
        assertEquals(1L, sold.getVersion());

        Seat locked = seatOf(SeatState.LOCKED);
        locked.setLockUserId(7L);
        assertThrows(IllegalStateTransitionException.class, locked::releaseByChange);
        assertEquals(SeatState.LOCKED, locked.getStatus());
    }

    @Test
    @DisplayName("U3-11 空闲座位直接出票链外的兜底：出票后锁定字段确已清空（ assertNotNull 防御）")
    void u3_sell_clearsLockFields() {
        Seat seat = seatOf(SeatState.LOCKED);
        seat.setLockUserId(7L);
        seat.setLockExpireAt(LOCK_UNTIL);
        assertNotNull(seat.getLockExpireAt());

        seat.sell();
        assertNull(seat.getLockUserId());
        assertNull(seat.getLockExpireAt());
        assertEquals(SeatState.SOLD, seat.getStatus());
    }
}
