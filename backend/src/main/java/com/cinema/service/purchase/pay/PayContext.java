package com.cinema.service.purchase.pay;

import com.cinema.model.Seat;
import com.cinema.model.TicketOrder;

import java.util.List;

/**
 * 支付流程上下文（模板方法 ① 校验钩子的产物，供 ②③④ 钩子共享）。
 *
 * @param command    原始命令
 * @param order      订单实体（校验钩子加载，已校验存在与归属）
 * @param heldSeats  该订单对应"本人仍持有的锁定座位"快照（校验钩子按 场次+锁定人+锁定态 查询，
 *                   按 seat.id 升序；权威判定在 ③ 状态迁移钩子的条件更新处）
 * @param showingId  场次 id（提前解引用，规避 clear 后懒加载失效）
 */
public record PayContext(PayCommand command, TicketOrder order, List<Seat> heldSeats, Long showingId) {
}
