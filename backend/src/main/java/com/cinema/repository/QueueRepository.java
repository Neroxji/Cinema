package com.cinema.repository;

import com.cinema.model.QueueRecord;
import org.springframework.data.jpa.repository.JpaRepository;

/** 候补队列仓库（表 queue）。 */
public interface QueueRepository extends JpaRepository<QueueRecord, Long> {
}
