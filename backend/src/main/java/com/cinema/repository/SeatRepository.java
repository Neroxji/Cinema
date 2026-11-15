package com.cinema.repository;

import com.cinema.model.Seat;
import com.cinema.model.enums.SeatState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 座位仓库（表 seat，并发核心表）。
 *
 * <p>基础查询 + task-03 追加的锁座条件更新（乐观锁，spec §4.3）：
 * <ul>
 *   <li>锁定：{@code UPDATE ... SET status='锁定',lock_expire_at=?,lock_user_id=?,version=version+1
 *       WHERE id=? AND status='空闲' AND version=?}</li>
 *   <li>超时释放：{@code UPDATE ... WHERE id=? AND status='锁定' AND lock_expire_at<?}——
 *       "释放前复查"以 WHERE 条件原子化（spec §3.2 锁定行 / §4.6）</li>
 * </ul>
 * 批量 JPQL 更新绕过持久化上下文，调用方（SeatLockService）更新后 flush+clear 再重读；
 * SQL 内 {@code version=version+1} 与 model/state 座位状态机的纯逻辑 bumpVersion 互为镜像，
 * 两条路径二选一执行，不对同一行重复生效。
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** 查询场次全部座位（座位图轮询 UC203 与种子断言使用）。 */
    List<Seat> findByShowingId(Long showingId);

    /** 按状态统计座位数（播种统计/测试断言）。 */
    long countByStatus(SeatState status);

    // ===================== task-03 追加：锁座条件更新（spec §4.3 / §3.2） =====================

    /** 预检取座位快照：一次取回"属于该场次且在所选 id 集合内"的座位（spec §4.1 事务外预检）。 */
    List<Seat> findByShowingIdAndIdIn(Long showingId, Collection<Long> ids);

    /**
     * 条件更新（乐观锁，spec §4.3 逐字语义）：
     * {@code UPDATE seat SET status=:toStatus, lock_expire_at=:lockExpireAt, lock_user_id=:lockUserId,
     * version=version+1 WHERE id=:id AND status=:expectedStatus AND version=:expectedVersion}
     *
     * <p>影响行数=0 即并发失败（他人已锁/已售/版本漂移）→ 服务层抛 SEAT_OCCUPIED 整体回滚
     * （UC204-4a：有且仅有一个请求锁定成功）。锁定调用形如 (FREE, v, LOCKED, expireAt, userId)；
     * 若后续模块需"释放他人过期锁"以外的定向释放，亦可复用本语句（状态对+版本按实取值传入）。
     */
    @Modifying
    @Query("""
            UPDATE Seat s
               SET s.status = :toStatus, s.lockExpireAt = :lockExpireAt,
                   s.lockUserId = :lockUserId, s.version = s.version + 1
             WHERE s.id = :id AND s.status = :expectedStatus AND s.version = :expectedVersion
            """)
    int conditionalUpdateOnStatusAndVersion(@Param("id") Long id,
                                            @Param("expectedStatus") SeatState expectedStatus,
                                            @Param("expectedVersion") Long expectedVersion,
                                            @Param("toStatus") SeatState toStatus,
                                            @Param("lockExpireAt") LocalDateTime lockExpireAt,
                                            @Param("lockUserId") Long lockUserId);

    /**
     * 超时释放条件更新（spec §3.2"释放前复查" + §4.6 条件更新释放，复查以 WHERE 原子化）：
     * {@code UPDATE seat SET status='空闲', lock_expire_at=NULL, lock_user_id=NULL, version=version+1
     * WHERE id=:id AND status='锁定' AND lock_expire_at < :expiredBefore}
     *
     * <p>影响行数=0 即复查失败（并发窗口内已被他人释放/重新锁定/售出）→ 服务层按重读后的实际状态判定。
     * 本卡由 SeatLockService 惰性释放（UC204-4b）使用；task-04 的 @Scheduled 扫描（UC206）
     * 复用同一语句（条件一致：status='锁定' 且已过期）。
     */
    @Modifying
    @Query("""
            UPDATE Seat s
               SET s.status = :toStatus, s.lockExpireAt = NULL, s.lockUserId = NULL,
                   s.version = s.version + 1
             WHERE s.id = :id AND s.status = :expectedStatus AND s.lockExpireAt < :expiredBefore
            """)
    int conditionalReleaseIfExpiredBefore(@Param("id") Long id,
                                          @Param("expectedStatus") SeatState expectedStatus,
                                          @Param("toStatus") SeatState toStatus,
                                          @Param("expiredBefore") LocalDateTime expiredBefore);

    // ===================== task-05 追加：候补递补与满座校验（UC304/305） =====================

    /** 场次内指定状态的第一个座位（按 seat.id 升序）——满座校验取非 SOLD 位、递补取第一个空闲位。 */
    Optional<Seat> findFirstByShowingIdAndStatusOrderByIdAsc(Long showingId, SeatState status);
}
