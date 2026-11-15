package com.cinema.model.enums;

/**
 * 用户角色（3 类），对应 PRD §2 参与者权限边界。
 *
 * <p>中文语义 ↔ 数据库存储值（{@code @Enumerated(EnumType.STRING)}，列 user.role）：
 * <ul>
 *   <li>AUDIENCE  = 观众（浏览/选座/支付/退改签/候补/通知）</li>
 *   <li>SCHEDULER = 排片员（影片/影厅/排期/票价管理）</li>
 *   <li>CHECKER   = 检票员（检票核销，只读订单）</li>
 * </ul>
 */
public enum UserRole {
    /** 观众。 */
    AUDIENCE,
    /** 排片员（管理端模块 A）。 */
    SCHEDULER,
    /** 检票员（检票核销 UC307）。 */
    CHECKER
}
