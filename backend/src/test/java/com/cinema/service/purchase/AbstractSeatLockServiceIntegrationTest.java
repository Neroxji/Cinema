package com.cinema.service.purchase;

import com.cinema.common.BizException;
import com.cinema.common.ErrorCode;
import com.cinema.model.Seat;
import com.cinema.model.Showing;
import com.cinema.model.User;
import com.cinema.model.enums.SeatState;
import com.cinema.repository.SeatRepository;
import com.cinema.repository.ShowingRepository;
import com.cinema.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I1/I2 · SeatLockService 锁座服务集成测试用例主体（测试计划 §3 I1/I2，P0 护栏）。
 *
 * <p>抽象基类只承载用例，profile 由子类声明：
 * <ul>
 *   <li>{@link SeatLockServiceIntegrationTest}：H2 内存库 test 档（默认 {@code mvn test} 即跑）</li>
 *   <li>{@link SeatLockServiceMySQLIntegrationTest}：容器 MySQL 8 mysql-test 档
 *       （{@code mvn test -Dtest=SeatLockServiceMySQLIntegrationTest -Dcinema.mysql.tests=true} 显式复跑），
 *       机制照搬 {@code SeedDataAssertMySQLTest}</li>
 * </ul>
 *
 * <p>隔离方式与 SeedDataAssertTest 一致：类级 {@code @Transactional} 回滚——
 * 测试对座位的锁定/释放全部回滚，不污染种子数据（292 座 / 空闲 242 等断言不受影响）。
 * 服务内 TransactionTemplate 以 REQUIRED 传播加入测试事务，行为语义等价。
 */
@SpringBootTest
@Transactional
@DisplayName("I1/I2 · SeatLockService 锁座并发方案（spec §4，用例主体）")
abstract class AbstractSeatLockServiceIntegrationTest {

    /** 锁定时长（演示档 30 秒，NFR2 双档——由 BookingConfig 提供，此处仅作断言窗宽参考）。 */
    private static final int LOCK_SECONDS = 30;

    @Autowired
    protected SeatLockService seatLockService;
    @Autowired
    protected SeatRepository seatRepository;
    @Autowired
    protected ShowingRepository showingRepository;
    @Autowired
    protected UserRepository userRepository;

    /** 演示观众（下单人）。 */
    protected User audience() {
        return userRepository.findByUsername("audience").orElseThrow();
    }

    /** 另一位用户（并发对手方 / 过期锁的持有者）。 */
    protected User otherUser() {
        return userRepository.findByUsername("scheduler").orElseThrow();
    }

    /** 取一个全部座位空闲的场次（种子有 5 个非满座场次，全部可用）。 */
    protected Showing freeShowing() {
        return showingRepository.findAll().stream()
                .filter(s -> seatRepository.findByShowingId(s.getId()).stream()
                        .allMatch(seat -> seat.getStatus() == SeatState.FREE))
                .findFirst().orElseThrow();
    }

    /** 取该场次前 n 个空闲座位（按 seat.id 升序）。 */
    protected List<Seat> freeSeats(Long showingId, int n) {
        List<Seat> seats = seatRepository.findByShowingId(showingId).stream()
                .filter(s -> s.getStatus() == SeatState.FREE)
                .sorted(Comparator.comparing(Seat::getId))
                .limit(n)
                .toList();
        assertEquals(n, seats.size(), "夹具：该场次空闲座位应足够");
        return seats;
    }

    // =====================================================================
    // I1 · 正常锁座（多座位，乱序传入）
    // =====================================================================

    @Test
    @DisplayName("I1 正常锁座：座位=锁定、lock_expire_at≈now+30s、version+1、lock_user_id 正确")
    void i1_normalLockSeats() {
        Showing showing = freeShowing();
        List<Seat> picked = freeSeats(showing.getId(), 2);
        Seat seatA = picked.get(0);
        Seat seatB = picked.get(1);
        long versionA = seatA.getVersion();
        long versionB = seatB.getVersion();
        LocalDateTime t0 = LocalDateTime.now();

        // 乱序传入（B 在前），验证服务按 seat.id 升序逐行条件更新并返回排序结果
        SeatLockService.LockResult result = seatLockService.lockSeats(
                showing.getId(), audience().getId(), List.of(seatB.getId(), seatA.getId()));
        LocalDateTime t1 = LocalDateTime.now();

        // 返回值：截止时间 ≈ now+30s（演示档），座位列表按 id 升序
        long offsetSeconds = Duration.between(t0, result.lockExpireAt()).getSeconds();
        assertTrue(offsetSeconds >= LOCK_SECONDS - 1 && offsetSeconds <= LOCK_SECONDS + 5,
                "锁定截止时间应为请求时刻+" + LOCK_SECONDS + "s（演示档），实际偏移 " + offsetSeconds + "s");
        assertEquals(showing.getId(), result.showingId());
        assertEquals(audience().getId(), result.userId());
        assertEquals(List.of(seatA.getId(), seatB.getId()), result.seatIds(),
                "多座位应按 seat.id 升序处理并返回");

        // 落库断言：状态/锁定人/截止时间/version
        for (Seat picked1 : picked) {
            Seat stored = seatRepository.findById(picked1.getId()).orElseThrow();
            assertEquals(SeatState.LOCKED, stored.getStatus(), "锁座后座位应为锁定态");
            assertEquals(audience().getId(), stored.getLockUserId(), "锁定人应为传入 userId");
            assertNotNull(stored.getLockExpireAt(), "锁定截止时间必须落库");
            long driftMillis = Math.abs(Duration.between(result.lockExpireAt(), stored.getLockExpireAt()).toMillis());
            assertTrue(driftMillis <= 1000,
                    "落库截止时间应与返回截止时间一致（DB 时间精度亚秒级舍入容忍），漂移=" + driftMillis + "ms");
        }
        assertEquals(versionA + 1, seatRepository.findById(seatA.getId()).orElseThrow().getVersion(),
                "座位 A 条件更新应 version+1");
        assertEquals(versionB + 1, seatRepository.findById(seatB.getId()).orElseThrow().getVersion(),
                "座位 B 条件更新应 version+1");
        assertTrue(t1.isBefore(result.lockExpireAt()), "提交后返回的截止时间应在未来");
    }

    // =====================================================================
    // I2 · 目标座位超时未清理再选（UC204-4b：先释放再按空闲锁定成功）
    // =====================================================================

    @Test
    @DisplayName("I2 超时未清理再选：先条件更新释放（复查过期）再按空闲锁定成功，version+2")
    void i2_expiredStaleLock_releasedThenLocked() {
        Showing showing = freeShowing();
        Seat target = freeSeats(showing.getId(), 1).get(0);
        long versionBeforeStale = target.getVersion();

        // 手工把该座位模拟为"超时未清理"的过期锁定（UC204-4b 前置态）：
        // 状态=LOCKED、锁定人为他人、lock_expire_at 已在过去（改到 60 秒前）。
        target.setStatus(SeatState.LOCKED);
        target.setLockUserId(otherUser().getId());
        target.setLockExpireAt(LocalDateTime.now().minusSeconds(60));
        seatRepository.saveAndFlush(target);
        long versionAfterStale = seatRepository.findById(target.getId()).orElseThrow().getVersion();
        assertEquals(versionBeforeStale + 1, versionAfterStale, "夹具：模拟过期锁定落库应 version+1");

        // 动作：另一观众锁定该座位——服务须先惰性释放（释放前复查 + 条件更新），再按空闲锁定
        SeatLockService.LockResult result = seatLockService.lockSeats(
                showing.getId(), audience().getId(), List.of(target.getId()));

        // 断言：最终为本次请求者锁定，而非失败（UC204-4b"先释放再按空闲处理"）
        Seat stored = seatRepository.findById(target.getId()).orElseThrow();
        assertEquals(SeatState.LOCKED, stored.getStatus(), "释放后应按空闲成功锁定");
        assertEquals(audience().getId(), stored.getLockUserId(), "锁定人应换为本次请求观众");
        assertEquals(audience().getId(), result.userId(), "返回值应携带本次请求用户（过期锁持有者是 otherUser）");
        assertTrue(stored.getLockExpireAt().isAfter(LocalDateTime.now().plusSeconds(LOCK_SECONDS - 5)),
                "新锁定截止时间应在未来（≈now+" + LOCK_SECONDS + "s）");
        assertEquals(versionAfterStale + 2, stored.getVersion(),
                "惰性释放（+1）与再锁定（+1）各触发一次条件更新，共 version+2");
    }

    // =====================================================================
    // 附：UC204-4a 顺序模拟——非空闲快速失败、多座位整体不落任何更新
    // =====================================================================

    @Test
    @DisplayName("附·UC204-4a 已锁定未过期座位：快速失败 10005，混选时另一空闲座位不受影响")
    void uc204_4a_occupiedSeatFastFail() {
        Showing showing = freeShowing();
        List<Seat> picked = freeSeats(showing.getId(), 3);
        Seat first = picked.get(0);
        Seat second = picked.get(1);
        Seat third = picked.get(2);

        // 第一窗口正常锁定 first
        seatLockService.lockSeats(showing.getId(), audience().getId(), List.of(first.getId()));

        // 第二窗口抢同一座位：快速失败，业务码 10005（座位已被占用）
        BizException conflict = assertThrows(BizException.class,
                () -> seatLockService.lockSeats(showing.getId(), otherUser().getId(), List.of(first.getId())),
                "已锁定且未过期的座位不得被第二窗口锁定");
        assertEquals(ErrorCode.SEAT_OCCUPIED.getCode(), conflict.getCode(), "并发失败应返回 10005");

        // 混选（一个空闲 + 一个已锁）：事务外预检即整体失败，任何座位不得被更新
        BizException mixed = assertThrows(BizException.class,
                () -> seatLockService.lockSeats(showing.getId(), otherUser().getId(),
                        List.of(second.getId(), first.getId())));
        assertEquals(ErrorCode.SEAT_OCCUPIED.getCode(), mixed.getCode());
        assertEquals(SeatState.FREE, seatRepository.findById(second.getId()).orElseThrow().getStatus(),
                "快速失败后，混选中的空闲座位必须保持空闲（整体不生效）");
        assertEquals(audience().getId(), seatRepository.findById(first.getId()).orElseThrow().getLockUserId(),
                "首窗口锁定不被破坏");
        assertEquals(SeatState.FREE, seatRepository.findById(third.getId()).orElseThrow().getStatus(),
                "未触及座位保持空闲");
    }
}
