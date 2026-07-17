package com.smartview.common.api;

import java.time.OffsetDateTime;

public class ApiResponse<T> {

    private final String code;
    private final String message;
    private final T data;
    private final String traceId;
    private final OffsetDateTime timestamp;

    private ApiResponse(String code, String message, T data, String traceId, OffsetDateTime timestamp) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = timestamp;
    }

    public static <T> ApiResponse<T> success(T data) {
        return of(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getMessage(), data);
    }

    public static <T> ApiResponse<T> failure(ResponseCode responseCode, String message) {
        return of(responseCode.getCode(), message, null);
    }

    public static <T> ApiResponse<T> of(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, TraceIdContext.currentTraceId(), OffsetDateTime.now());
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public String getTraceId() {
        return traceId;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }
}
