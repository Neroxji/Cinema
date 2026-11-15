package com.cinema.repository;

import com.cinema.model.Showing;
import org.springframework.data.jpa.repository.JpaRepository;

/** 场次仓库（表 showing）。 */
public interface ShowingRepository extends JpaRepository<Showing, Long> {
}
