package com.smartview.common.exception;

import com.smartview.common.api.ResponseCode;
import org.springframework.http.HttpStatus;

/**
 * 业务异常类
 * <p>
 * 用于封装业务逻辑处理过程中的异常情况。
 * 相比系统异常，业务异常通常是可预期的，需要向用户返回友好的错误提示。
 * </p>
 * <p>
 * 该异常会被全局异常处理器捕获，转换为标准的 API 响应格式。
 * </p>
 */
public class BusinessException extends RuntimeException {

    /** 响应状态码 */
    private final ResponseCode responseCode;

    /** HTTP 状态码 */
    private final HttpStatus httpStatus;

    /**
     * 构造函数（使用默认业务错误码和 400 状态）
     *
     * @param message 错误消息
     */
    public BusinessException(String message) {
        this(ResponseCode.BUSINESS_ERROR, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 构造函数（使用自定义响应码和默认 400 状态）
     *
     * @param responseCode 响应状态码
     * @param message      错误消息
     */
    public BusinessException(ResponseCode responseCode, String message) {
        this(responseCode, message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 构造函数（完全自定义）
     *
     * @param responseCode 响应状态码
     * @param message      错误消息
     * @param httpStatus   HTTP 状态码
     */
    public BusinessException(ResponseCode responseCode, String message, HttpStatus httpStatus) {
        super(message);
        this.responseCode = responseCode;
        this.httpStatus = httpStatus;
    }

    public ResponseCode getResponseCode() {
        return responseCode;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
