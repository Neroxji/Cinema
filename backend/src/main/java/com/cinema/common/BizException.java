package com.cinema.common;

/**
 * 业务异常（携带 {@link ErrorCode}，由 GlobalExceptionHandler 统一转换为 ApiResponse 外壳）。
 *
 * <p>用法：{@code throw new BizException(ErrorCode.NOT_FOUND, "订单不存在");}
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 便捷取码（等价于 getErrorCode().getCode()）。 */
    public int getCode() {
        return errorCode.getCode();
    }
}
