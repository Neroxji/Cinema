package com.cinema.service.aftermarket;

import com.cinema.repository.NotificationRepository;
import com.cinema.model.Notification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知中心查询服务（UC306，task-05）。
 *
 * <p>// @UseCase: UC306(通知中心列表——本人通知，新通知在前)
 *
 * <p>read_flag 语义（编排者契约决策 4，晨报记录）：GET /api/notifications 仅列表、
 * 只读不翻转 read_flag（20 接口清单之外不加"标记已读"端点）；read_flag=false 表示未读，
 * 当前系统无任何置读入口，全部通知保持未读返回，语义如实在字段表-C模块.md 记录。
 */
// @UseCase: UC306
@Service
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    public NotificationQueryService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /** 通知列表元素。 */
    public record NotificationView(Long id, String type, String title, String content,
                                   boolean readFlag, LocalDateTime createdAt) {
    }

    /** 我的通知列表（UC306）：按 id 降序（新通知在前）。 */
    @Transactional(readOnly = true)
    public List<NotificationView> listMyNotifications(Long userId) {
        List<Notification> notices = notificationRepository.findByUserIdOrderByIdDesc(userId);
        return notices.stream()
                .map(notice -> new NotificationView(notice.getId(), notice.getType(), notice.getTitle(),
                        notice.getContent(), notice.isReadFlag(), notice.getCreatedAt()))
                .toList();
    }
}
