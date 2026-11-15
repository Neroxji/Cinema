package com.cinema.repository;

import com.cinema.model.Showing;
import com.cinema.model.enums.ShowingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 场次仓库（表 showing）。 */
public interface ShowingRepository extends JpaRepository<Showing, Long> {

    /**
     * 查询影厅下指定状态的场次（task-02 追加）。
     * UC103-2a 时段冲突校验用：取同影厅 PUBLISHED 场次做时间区间重叠比对
     * （CANCELED 场次不占用时段，不参与冲突）。
     */
    List<Showing> findByHallIdAndStatus(Long hallId, ShowingStatus status);
}
