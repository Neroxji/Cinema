package com.cinema.repository;

import com.cinema.model.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 站内通知仓库（表 notification）。
 *
 * <p>task-05 追加（追加式，不改既有方法）：递补邀请检索（UC305 响应时限推算依据）。
 * 邀请通知的 content 内嵌递补凭据令牌（"递补凭据Q{queueId}，"），以 LIKE 检索最新一条——
 * 编排者契约决策 3：递补截止 = 邀请通知 created_at + BookingConfig.queueResponseLimit，不加列。
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 按类型与内容片段检索通知（id 降序 = 最新在前，Pageable 取第一条）。
     * 递补邀请查找：type=QUEUE_MATCHED 且 content 含"递补凭据Q{queueId}，"。
     */
    List<Notification> findByTypeAndContentContainingOrderByIdDesc(String type, String content, Pageable pageable);

    /** 我的通知列表（UC306）：按 id 降序（新通知在前）。 */
    List<Notification> findByUserIdOrderByIdDesc(Long userId);
}
