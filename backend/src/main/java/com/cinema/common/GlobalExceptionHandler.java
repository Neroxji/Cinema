package com.cinema.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理（@RestControllerAdvice）：任何未捕获异常都转换为统一响应外壳
 * {@code {code, message, data}}（spec §7.2），三类覆盖：
 * <ol>
 *   <li>业务异常 BizException → 按 ErrorCode 的 HTTP 状态与消息返回</li>
 *   <li>参数校验失败（@Valid/@Validated）→ 400 + INVALID_PARAM</li>
 *   <li>兜底 Exception → 500 + SYSTEM_ERROR</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：拦截器/后续 service 抛出，按错误码映射 HTTP 状态（401/403/400/404/409…）。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBizException(BizException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    /** 参数校验失败（@Valid 请求体 / @Validated 表单绑定，Spring 6 中两者同为 BindException 子类）。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(GlobalExceptionHandler::formatFieldError)
                .orElse("请检查请求参数");
        log.warn("参数校验失败：{}", detail);
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_PARAM, "参数非法：" + detail));
    }

    /** 参数校验失败（@RequestParam/@PathVariable 上的约束注解）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .findFirst()
                .map(GlobalExceptionHandler::formatViolation)
                .orElse("请检查请求参数");
        log.warn("参数校验失败：{}", detail);
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_PARAM, "参数非法：" + detail));
    }

    /** 请求体缺失或 JSON 格式错误。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体不可读：{}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_PARAM, "参数非法：请求体缺失或格式错误"));
    }

    /** 兜底：未预期异常一律 500 + SYSTEM_ERROR，避免堆栈泄露给前端。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("系统未预期异常", e);
        return ResponseEntity.status(ErrorCode.SYSTEM_ERROR.getHttpStatus())
                .body(ApiResponse.fail(ErrorCode.SYSTEM_ERROR));
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }

    private static String formatViolation(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + " " + violation.getMessage();
    }
}
