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
    SEAT_OCCUPIED(10005, "座位已被占用，请刷新座位图后重试", HttpStatus.CONFLICT);

    // ---------- A 模块段 11xxx（预留：排片与票价，task-02 起新增） ----------
    // ---------- B 模块段 12xxx（预留：在线购票） ----------
    // ---------- C 模块段 13xxx（预留：订单售后与通知） ----------

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
