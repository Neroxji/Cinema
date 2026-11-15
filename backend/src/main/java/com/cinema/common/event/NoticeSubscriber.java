package com.cinema.common.event;

import com.cinema.model.Notification;
import com.cinema.model.User;
import com.cinema.repository.NotificationRepository;
import com.cinema.repository.UserRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 通知订阅者（观察者模式 · Observer 角色）：订阅 4 类领域事件写站内通知（UC306，spec §7.7）。
 *
 * <p>// @Pattern: Observer(Observer角色——通知订阅者,
 * <br>对应 notification 表 4 类 type), @UseCase: UC306(通知中心)/205/206/302/305
 *
 * <p>事件→通知映射（type 取值与 Notification 实体注释、schema.sql 同口径）：
 * <ul>
 *   <li>{@link TicketIssuedEvent}  → TICKET_ISSUED（出票成功，UC205）</li>
 *   <li>{@link SeatReleasedEvent}  → SEAT_RELEASED（座位释放：退票/超时/主动放弃/改签）</li>
 *   <li>{@link QueueMatchedEvent}  → QUEUE_MATCHED（候补递补成功，UC305）</li>
 *   <li>{@link RefundCompletedEvent} → REFUND_COMPLETED（模拟退款到账，UC302）</li>
 * </ul>
 *
 * <p>写库时序：@EventListener 同步执行、随发布方事务提交/回滚（I3/I6/I7 断言依赖此语义）；
 * 接收人 userId 为空的事件（如无主的座位释放）跳过不写。
 */
// @Pattern: Observer(Observer/通知订阅者), @UseCase: UC306/205/206/302/305
@Component
public class NoticeSubscriber {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    public NoticeSubscriber(UserRepository userRepository, NotificationRepository notificationRepository) {
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
    }

    /** 出票成功（UC205）→ TICKET_ISSUED 通知购票观众。 */
    @EventListener
    public void onTicketIssued(TicketIssuedEvent event) {
        save(event.userId(), "TICKET_ISSUED", "出票成功",
                "订单 " + event.orderNo() + " 已出票，共 " + event.seatIds().size() + " 张票，"
                        + "合计 ¥" + event.totalAmount() + "，请凭取票码入场。");
    }

    /** 座位释放（UC206 超时 / UC302 退票 / 主动放弃 / 改签）→ SEAT_RELEASED 通知原占用人。 */
    @EventListener
    public void onSeatReleased(SeatReleasedEvent event) {
        if (event.userId() == null) {
            return;
        }
        int seatCount = event.seatIds() == null ? 0 : event.seatIds().size();
        String content = switch (event.reason()) {
            case LOCK_TIMEOUT -> "您在场次 " + event.showingId() + " 的 " + seatCount
                    + " 个座位锁定已超时自动释放，订单已取消，请重新选座。";
            case GIVE_UP -> "您已重新选座，原锁定座位（场次 " + event.showingId() + "，共 "
                    + seatCount + " 个）已释放。";
            case REFUND -> "您的退票已释放座位（场次 " + event.showingId() + "，共 " + seatCount + " 个）。";
            case CHANGE_SHOWING -> "改签已释放原场次座位（场次 " + event.showingId() + "，共 "
                    + seatCount + " 个），请前往新场次查看。";
        };
        save(event.userId(), "SEAT_RELEASED", "座位锁定已释放", content);
    }

    /** 退款到账（UC302 模拟退款 / UC205-4a 支付作废退款）→ REFUND_COMPLETED 通知退款接收人。 */
    @EventListener
    public void onRefundCompleted(RefundCompletedEvent event) {
        save(event.userId(), "REFUND_COMPLETED", "退款到账",
                "订单 " + event.orderNo() + " 的退款 ¥" + event.refundAmount() + " 已到账（模拟支付渠道）。");
    }

    /** 候补递补成功（UC305）→ QUEUE_MATCHED 通知递补观众。 */
    @EventListener
    public void onQueueMatched(QueueMatchedEvent event) {
        save(event.userId(), "QUEUE_MATCHED", "候补递补成功",
                "您在场次 " + event.showingId() + " 的候补已递补成功，请在响应时限内确认锁座。");
    }

    /** 落一条站内通知（随发布方事务提交/回滚）。 */
    private void save(Long userId, String type, String title, String content) {
        Notification notice = new Notification();
        User receiver = userRepository.getReferenceById(userId);
        notice.setUser(receiver);
        notice.setType(type);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setReadFlag(false);
        notice.setCreatedAt(LocalDateTime.now());
        notificationRepository.save(notice);
    }
}
