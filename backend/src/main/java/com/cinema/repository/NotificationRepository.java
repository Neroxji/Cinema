package com.cinema.repository;

import com.cinema.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

/** 站内通知仓库（表 notification）。 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
