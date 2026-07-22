package com.smartview.common.exception;

import java.util.stream.Collectors;

import com.smartview.common.api.ApiResponse;
import com.smartview.common.api.ResponseCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 * <p>
 * 统一拦截和处理应用中抛出的各类异常，将异常转换为标准的 API 响应格式。
 * 确保所有异常响应符合 API 契约规范，并向前端提供友好的错误提示。
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常
     * <p>
     * 业务异常通常是可预期的，直接返回异常中携带的状态码和消息。
     * </p>
     *
     * @param exception 业务异常
     * @return 标准错误响应
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return ResponseEntity.status(exception.getHttpStatus())
                .body(ApiResponse.failure(exception.getResponseCode(), exception.getMessage()));
    }

    /**
     * 处理 @RequestBody 参数校验失败异常
     * <p>
     * 当使用 @Valid 或 @Validated 注解校验请求体参数失败时触发。
     * </p>
     *
     * @param exception 参数校验异常
     * @return 标准错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        return validationFailure(buildFieldErrorMessage(exception));
    }

    /**
     * 处理表单绑定异常
     * <p>
     * 当使用 @ModelAttribute 绑定表单参数校验失败时触发。
     * </p>
     *
     * @param exception 绑定异常
     * @return 标准错误响应
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBindException(BindException exception) {
        return validationFailure(buildFieldErrorMessage(exception));
    }

    /**
     * 处理约束违反异常
     * <p>
     * 当使用 @Validated 注解在方法级别校验参数（如路径变量、请求参数）失败时触发。
     * </p>
     *
     * @param exception 约束违反异常
     * @return 标准错误响应
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .collect(Collectors.joining("；"));
        return validationFailure(message);
    }

    /**
     * 处理请求体缺失或 JSON 无法反序列化的异常。
     *
     * <p>该异常发生在进入 Controller 和业务校验之前，需要显式映射为 400，
     * 同时隐藏 Jackson、字段类型等底层解析细节。</p>
     *
     * @param exception 请求体读取异常
     * @return 标准错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(ResponseCode.BAD_REQUEST, "请求体格式错误，请检查后重试"));
    }

    /**
     * 处理请求参数错误异常
     * <p>
     * 包括：缺少必需参数、参数类型不匹配等情况。
     * </p>
     *
     * @param exception 请求参数异常
     * @return 标准错误响应
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(ResponseCode.BAD_REQUEST, "请求参数错误，请检查后重试"));
    }

    /**
     * 处理资源未找到异常
     * <p>
     * 当请求的 URL 路径或资源不存在时触发。
     * </p>
     *
     * @param exception 资源未找到异常
     * @return 标准错误响应
     */
    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure(ResponseCode.NOT_FOUND, "请求的资源不存在"));
    }

    /**
     * 处理未被捕获的其他异常（兜底处理）
     * <p>
     * 对于未预期的系统异常，只向用户返回稳定的中文提示，
     * 详细的堆栈信息保留在服务端日志中，避免泄露系统内部细节。
     * </p>
     *
     * @param exception 未捕获的异常
     * @return 标准错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        // 兜底异常只向用户返回稳定中文文案，详细堆栈保留在服务端日志中。
        log.error("Unhandled server exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(ResponseCode.INTERNAL_ERROR, "服务开小差了，请稍后再试"));
    }

    /**
     * 构建参数校验失败响应
     *
     * @param detail 详细错误信息
     * @return 标准错误响应
     */
    private ResponseEntity<ApiResponse<Void>> validationFailure(String detail) {
        String message = detail == null || detail.isBlank()
                ? "参数校验失败，请检查输入内容"
                : "参数校验失败：" + detail;
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.failure(ResponseCode.VALIDATION_FAILED, message));
    }

    /**
     * 构建字段错误消息
     * <p>
     * 将多个字段错误信息拼接为一条消息，格式为："字段名 错误信息；字段名 错误信息"。
     * </p>
     *
     * @param exception 绑定异常
     * @return 拼接后的错误消息
     */
    private String buildFieldErrorMessage(BindException exception) {
        return exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("；"));
    }
}
