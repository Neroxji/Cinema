package com.cinema.common.event;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 领域事件发布器（观察者模式 · Subject/广播器角色，spec §5/§7.7）。
 *
 * <p>// @Pattern: Observer(Subject角色——领域事件统一广播入口,
 * <br>订阅者见 NoticeSubscriber 等 @EventListener Bean), @UseCase: UC205/206/302/305/306
 *
 * <p>实现选型（编排者契约决策，晨报记录）：基于 Spring 事件机制做<b>轻量自研包装</b>——
 * 不引消息中间件（spec 进程视图：单体单进程无 MQ），同步广播、与发布方同事务：
 * <ul>
 *   <li>事件在本事务内广播，订阅者（如 NoticeSubscriber）写库随同一事务提交/回滚——
 *       支付回滚（UC205-5a/I6）时出票通知一并回滚，无脏数据；</li>
 *   <li>订阅失败向上传播：释放事务整体回滚、下一轮扫描重试（宁可重试不丢事件）。</li>
 * </ul>
 *
 * <p>事件消息体（本包 4 个 record，task-01 定义，本卡不改）：{@link TicketIssuedEvent} /
 * {@link SeatReleasedEvent} / {@link RefundCompletedEvent} / {@link QueueMatchedEvent}。
 */
// @Pattern: Observer(Subject/事件广播器), @UseCase: UC205/206/302/305/306
@Component
public class DomainEventPublisher {

    private final ApplicationEventPublisher publisher;

    public DomainEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 同步广播领域事件（同事务语义：订阅者写库与发布方同事务提交/回滚）。
     * 事件类型见本包 4 个 record；订阅者按事件类型分派（@EventListener）。
     */
    public void publish(Object event) {
        publisher.publishEvent(event);
    }
}
