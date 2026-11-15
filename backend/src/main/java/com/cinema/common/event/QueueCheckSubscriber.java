package com.cinema.common.event;

import com.cinema.service.aftermarket.QueueService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 候补检查订阅者（观察者模式 · Observer 角色，spec §5/§7.7——观察者第三件，task-05 交付）。
 *
 * <p>// @Pattern: Observer(Observer角色——候补检查订阅者,
 * <br>座位释放事件→候补递补检查), @UseCase: UC305(候补递补)/UC302(退票触发)
 *
 * <p>订阅语义（spec §7.7 事件清单"座位释放 → 候补检查订阅者触发递补（UC305）+ 通知订阅者"）：
 * 收到 {@link SeatReleasedEvent}（退票 UC302 / 锁定超时 UC206 / 观众主动放弃 / 改签释放 UC303）
 * 后，对该场次执行候补递补检查——
 * <ul>
 *   <li>候补队列非空：按 rank 取第一位发出递补邀请通知（含递补凭据 queueId 供 claim，
 *       响应时限内确认即锁座，I8）；</li>
 *   <li>候补队列为空：无动作，座位对所有人可售（UC302-6b，I10）。</li>
 * </ul>
 * 检查在发布方事务内同步执行（邀请通知随释放事务提交/回滚，与 NoticeSubscriber 同语义）。
 */
// @Pattern: Observer(Observer/候补检查订阅者), @UseCase: UC305/UC302
@Component
public class QueueCheckSubscriber {

    private final QueueService queueService;

    public QueueCheckSubscriber(QueueService queueService) {
        this.queueService = queueService;
    }

    /** 座位释放（退票/超时/主动放弃/改签）→ 候补递补检查（UC305，队列空则无动作）。 */
    @EventListener
    public void onSeatReleased(SeatReleasedEvent event) {
        queueService.onSeatReleased(event.showingId());
    }
}
