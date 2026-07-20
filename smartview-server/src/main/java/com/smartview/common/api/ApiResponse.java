package com.smartview.common.api;

import java.time.OffsetDateTime;

/**
 * 统一 API 响应封装类
 * <p>
 * 该类用于统一封装所有 REST API 的响应格式，确保前后端交互的一致性。
 * 响应结构包含：状态码、消息、业务数据、追踪 ID 和时间戳。
 * </p>
 *
 * @param <T> 响应数据的泛型类型
 */
public class ApiResponse<T> {

    /** 响应状态码 */
    private final String code;

    /** 响应消息描述 */
    private final String message;

    /** 响应业务数据（成功时返回，失败时为 null） */
    private final T data;

    /** 分布式追踪 ID，用于跨服务请求链路追踪 */
    private final String traceId;

    /** 响应时间戳 */
    private final OffsetDateTime timestamp;

    /**
     * 私有构造函数，通过静态工厂方法创建实例
     *
     * @param code      响应状态码
     * @param message   响应消息
     * @param data      响应数据
     * @param traceId   追踪 ID
     * @param timestamp 时间戳
     */
    private ApiResponse(String code, String message, T data, String traceId, OffsetDateTime timestamp) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = timestamp;
    }

    /**
     * 创建成功响应
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功的 API 响应对象
     */
    public static <T> ApiResponse<T> success(T data) {
        return of(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getMessage(), data);
    }

    /**
     * 创建失败响应
     *
     * @param responseCode 响应状态码枚举
     * @param message      自定义错误消息
     * @param <T>          数据类型
     * @return 失败的 API 响应对象
     */
    public static <T> ApiResponse<T> failure(ResponseCode responseCode, String message) {
        return of(responseCode.getCode(), message, null);
    }

    /**
     * 创建自定义响应
     *
     * @param code    响应状态码
     * @param message 响应消息
     * @param data    响应数据
     * @param <T>     数据类型
     * @return API 响应对象
     */
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
