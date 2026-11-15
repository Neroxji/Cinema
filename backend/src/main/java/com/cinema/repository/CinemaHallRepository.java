package com.cinema.repository;

import com.cinema.model.CinemaHall;
import org.springframework.data.jpa.repository.JpaRepository;

/** 影厅仓库（表 cinema_hall）。 */
public interface CinemaHallRepository extends JpaRepository<CinemaHall, Long> {
}
