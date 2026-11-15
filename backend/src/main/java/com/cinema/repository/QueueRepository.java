package com.cinema.repository;

import com.cinema.model.QueueRecord;
import com.cinema.model.enums.QueueStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 候补队列仓库（表 queue）。
 *
 * <p>task-05 追加（追加式，不改既有方法）：候补加入（UC304）、按 rank 递补（UC305）、
 * 递补超时扫描。注意 {@code rank} 为 MySQL 8/H2 保留字——原生聚合查询须反引号转义
 * （与 schema.sql 同口径）；派生查询走实体字段名，由 Hibernate 映射无保留字问题。
 */
public interface QueueRepository extends JpaRepository<QueueRecord, Long> {

    /** 场次内指定状态的候补记录，按 rank 升序（递补取第一位：UC305）。 */
    List<QueueRecord> findByShowingIdAndStatusOrderByRankAscIdAsc(Long showingId, QueueStatus status);

    /** 全部指定状态的候补记录，按（场次, rank）升序（递补超时扫描遍历用）。 */
    List<QueueRecord> findByStatusOrderByShowingIdAscRankAsc(QueueStatus status);

    /** 重复候补校验（UC304：同用户同场次排队中的记录存在即拒绝）。 */
    boolean existsByShowingIdAndUserIdAndStatus(Long showingId, Long userId, QueueStatus status);

    /** 场次当前最大候补序号（无记录返回 null；rank 分配 = max+1，过期记录占号不重排）。 */
    @Query(value = "SELECT MAX(`rank`) FROM queue WHERE showing_id = :showingId", nativeQuery = true)
    Integer findMaxRankByShowingId(@Param("showingId") Long showingId);
}
