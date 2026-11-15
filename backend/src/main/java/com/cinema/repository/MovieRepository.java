package com.cinema.repository;

import com.cinema.model.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

/** 影片仓库（表 movie）。 */
public interface MovieRepository extends JpaRepository<Movie, Long> {
}
