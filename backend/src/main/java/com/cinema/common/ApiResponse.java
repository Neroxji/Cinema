package com.cinema.common;

/**
 * 统一响应外壳（spec §7.2：所有接口返回 {@code {code, message, data}}）。
 *
 * <p>code 约定：0 = 成功；非 0 = 业务错误码（见 {@link ErrorCode}）。
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

    /** 业务状态码：0 成功，非 0 见 ErrorCode。 */
    private int code;

    /** 提示消息（面向用户的中文描述）。 */
    private String message;

    /** 业务数据（可为 null）。 */
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（带数据）。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "成功", data);
    }

    /** 成功（无数据）。 */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "成功", null);
    }

    /** 失败（按错误码默认消息）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败（错误码 + 自定义消息，用于补充上下文信息）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null);
    }

    /** 失败（自定义码与消息，模块段错误码预留时使用）。 */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
