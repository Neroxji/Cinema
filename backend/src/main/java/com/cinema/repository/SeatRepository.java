package com.cinema.repository;

import com.cinema.model.Seat;
import com.cinema.model.enums.SeatState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 座位仓库（表 seat，并发核心表）。
 *
 * <p>本卡仅提供基础查询；锁座条件更新（乐观锁 UPDATE ... WHERE status='空闲' AND version=?）
 * 属 task-03，届时在此追加 @Modifying 方法。
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /** 查询场次全部座位（座位图轮询 UC203 与种子断言使用）。 */
    List<Seat> findByShowingId(Long showingId);

    /** 按状态统计座位数（播种统计/测试断言）。 */
    long countByStatus(SeatState status);
}
