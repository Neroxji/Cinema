package com.cinema.common;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码枚举（统一响应外壳的 code 段位规划，spec §7.2"业务错误码按模块自定义"）。
 *
 * <p>段位约定（预留，后续模块不得越段）：
 * <ul>
 *   <li>10000~10099：公共段（本卡实现）</li>
 *   <li>11000~11999：A 模块·排片与票价（task-02 起按需新增，如场次冲突、策略参数非法）</li>
 *   <li>12000~12999：B 模块·在线购票（如余额/支付网关模拟类错误）</li>
 *   <li>13000~13999：C 模块·订单售后与通知（如退票时限、候补状态类错误）</li>
 * </ul>
 */
public enum ErrorCode {

    // ---------- 公共段 100xx ----------
    /** 系统异常（兜底）。 */
    SYSTEM_ERROR(10000, "系统异常，请稍后重试", HttpStatus.INTERNAL_SERVER_ERROR),
    /** 未登录或会话已过期（拦截器抛出，HTTP 401）。 */
    NOT_LOGGED_IN(10001, "未登录或会话已过期", HttpStatus.UNAUTHORIZED),
    /** 无权限（角色校验不通过，HTTP 403）。 */
    NO_PERMISSION(10002, "无权限执行该操作", HttpStatus.FORBIDDEN),
    /** 参数非法（@Valid 校验失败 / 请求体不可读 / 业务参数越界）。 */
    INVALID_PARAM(10003, "参数非法", HttpStatus.BAD_REQUEST),
    /** 资源不存在（订单/场次/影片等 id 无效）。 */
    NOT_FOUND(10004, "资源不存在", HttpStatus.NOT_FOUND),
    /** 座位已被占用（并发锁座失败的统一提示，UC204-4a；HTTP 409 冲突）。 */
    SEAT_OCCUPIED(10005, "座位已被占用，请刷新座位图后重试", HttpStatus.CONFLICT),

    // ---------- A 模块段 11xxx（排片与票价，task-02 启用） ----------
    /** 影片不存在（UC104 编辑/上下架与 UC103 创建场次时 id 无效）。 */
    MOVIE_NOT_FOUND(11001, "影片不存在", HttpStatus.NOT_FOUND),
    /** 影厅时段冲突：同影厅已有 PUBLISHED 场次与新建场次时间区间重叠（UC103-2a）。 */
    HALL_TIME_CONFLICT(11002, "该影厅该时段已有场次，请重选时间", HttpStatus.CONFLICT),
    /** 票价策略参数非法：折扣系数∉(0,1)、分区价≤0、参数缺失等（UC105-3a / UC103-3）。 */
    INVALID_STRATEGY_PARAM(11003, "票价策略参数非法", HttpStatus.BAD_REQUEST),
    /** 场次存在已售/锁定座位，禁止取消（UC104 / 测试计划 I14；锁定中一并保守拒绝）。 */
    SHOWING_CANCEL_FORBIDDEN(11004, "场次存在已售或锁定中的座位，禁止取消", HttpStatus.CONFLICT),
    /** 场次不存在（UC104 取消 / UC105 策略配置时 id 无效）。 */
    SHOWING_NOT_FOUND(11005, "场次不存在", HttpStatus.NOT_FOUND),
    /** 场次当前状态不允许取消（非 PUBLISHED，如已取消/已结束）。 */
    SHOWING_NOT_CANCELABLE(11006, "场次当前状态不允许取消", HttpStatus.CONFLICT),
    /** 影片已下架，终止创建场次（UC103-5a）。 */
    MOVIE_OFF_SHOW(11007, "影片已下架，无法创建场次", HttpStatus.CONFLICT),
    /** 影厅不存在（UC103 创建场次时 id 无效）。 */
    HALL_NOT_FOUND(11008, "影厅不存在", HttpStatus.NOT_FOUND),

    // ---------- B 模块段 12xxx（在线购票，task-04 启用） ----------
    /** 模拟支付网关返回失败（UC205-3a）：订单保持待支付，座位维持锁定至倒计时结束。 */
    PAY_GATEWAY_FAILED(12001, "支付失败，请稍后重试（座位锁定保持至倒计时结束）", HttpStatus.PAYMENT_REQUIRED),
    /** 支付作废（UC205-4a）：支付时座位已被超时释放/订单已取消，模拟退款，提示重新选座。 */
    PAY_VOID_SEAT_RELEASED(12002, "座位锁定已释放，支付已作废并退款，请重新选座", HttpStatus.CONFLICT),
    /** 订单当前状态不可支付（已出票重复支付/已检票/已退票等，状态机拒绝）。 */
    ORDER_NOT_PAYABLE(12003, "订单当前状态不可支付", HttpStatus.CONFLICT),
    /** 订单不存在（支付/售后接口的 id 无效）。 */
    ORDER_NOT_FOUND(12004, "订单不存在", HttpStatus.NOT_FOUND),
    /** 非本人订单（越权支付，UC205 前置条件"本人订单"）。 */
    ORDER_FORBIDDEN(12005, "无权操作他人订单", HttpStatus.FORBIDDEN),
    /** 用户名已被注册（UC201 注册重名）。 */
    USERNAME_TAKEN(12006, "用户名已被注册，请更换用户名", HttpStatus.CONFLICT),
    /** 用户名或密码错误（UC201 登录失败，不区分具体原因防枚举）。 */
    LOGIN_FAILED(12007, "用户名或密码错误", HttpStatus.UNAUTHORIZED),

    // ---------- C 模块段 13xxx（订单售后与通知，task-05 启用） ----------
    /** 订单当前状态不可退票（UC302-4a：仅"已出票且未检票"可退；已检票/已退票/已取消/待支付均拒绝）。 */
    ORDER_NOT_REFUNDABLE(13001, "订单当前状态不可退票（仅已出票且未检票的订单可退）", HttpStatus.CONFLICT),
    /** 已过退票截止（UC302-4b：开映前 BookingConfig.refundDeadline（默认 2 小时）停止受理退票）。 */
    REFUND_DEADLINE_PASSED(13002, "已过退票截止（开映前 2 小时停止受理退票），不可退票", HttpStatus.CONFLICT),
    /** 重复候补（UC304：同一用户同一场次存在排队中的候补记录，拒绝重复加入）。 */
    QUEUE_ALREADY_JOINED(13003, "您已在该场次候补队列中排队，请勿重复候补", HttpStatus.CONFLICT),
    /** 候补记录不存在或状态不符（UC305 claim：未收到递补/已递补/已过期/已取消的记录不可确认）。 */
    QUEUE_RECORD_INVALID(13004, "候补记录不存在或状态不符，无法确认递补（请先等待递补通知）", HttpStatus.CONFLICT),
    /** 递补确认超时（UC305：响应时限（BookingConfig.queueResponseLimit，默认 3 分钟）内未确认，已顺延下一位）。 */
    QUEUE_CLAIM_EXPIRED(13005, "递补确认已超时（响应时限内未确认，资格已顺延下一位候补）", HttpStatus.CONFLICT),
    /** 递补座位已被他人选购（UC305 claim 锁座时座位已非空闲，候选继续排队等下一次递补）。 */
    QUEUE_SEAT_TAKEN(13006, "递补座位已被其他观众选购，请继续排队等待下一次递补", HttpStatus.CONFLICT),
    /** 订单当前状态不可核销（UC307-3a：仅"已出票"可核销，待支付/已退票/已取消拒绝）。 */
    CHECKIN_STATUS_MISMATCH(13007, "订单当前状态不可核销，仅已出票订单可核销入场", HttpStatus.CONFLICT),
    /** 订单已检票重复核销（UC307-5a 幂等拒绝：核销仅一次生效，重复提交返回本码）。 */
    CHECKIN_ALREADY_CHECKED_IN(13008, "订单已检票核销，请勿重复核销", HttpStatus.CONFLICT),
    /** 开映超 30 分钟核销转人工（UC307-3b：超过宽限时段拒绝自助核销）。 */
    CHECKIN_MANUAL_REQUIRED(13009, "本场次开映已超过 30 分钟，请转人工窗口处理", HttpStatus.CONFLICT),
    /** 订单当前状态不可改签（UC303：仅"已出票"订单可改签）。 */
    CHANGE_NOT_ALLOWED(13010, "订单当前状态不可改签（仅已出票的订单可改签）", HttpStatus.CONFLICT),
    /** 已过改签截止（UC303：开映前 BookingConfig.refundDeadline（默认 2 小时）停止受理改签）。 */
    CHANGE_DEADLINE_PASSED(13011, "已过改签截止（开映前 2 小时停止受理改签），不可改签", HttpStatus.CONFLICT),
    /** 改签目标场次无效（UC303：须为同影片其他已发布、未开映且余座充足的场次）。 */
    CHANGE_TARGET_INVALID(13012, "改签目标场次无效（须为同影片其他已发布、未开映且余座充足的场次）", HttpStatus.BAD_REQUEST),
    /** 每单限改签一次（UC303 业务规则：change_from_showing_id 已留痕的订单拒绝二次改签）。 */
    CHANGE_LIMIT_REACHED(13013, "每单仅限改签一次，本订单已改签过", HttpStatus.CONFLICT),
    /** 场次不满足候补条件（UC304：仅"未开映的满座场次"可加入候补）。 */
    QUEUE_JOIN_REJECTED(13014, "当前场次不满足候补条件（仅未开映的满座场次可加入候补）", HttpStatus.CONFLICT);

    private final int code;

    private final String message;

    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
