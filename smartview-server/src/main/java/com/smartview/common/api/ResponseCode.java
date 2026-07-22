package com.smartview.common.api;

/**
 * API 响应状态码枚举
 * <p>
 * 定义了系统中所有标准的 API 响应状态码和对应的消息描述。
 * 状态码采用语义化命名，便于前端和日志分析识别。
 * </p>
 */
public enum ResponseCode {
    /** 操作成功 */
    SUCCESS("SUCCESS", "操作成功"),

    /** 请求参数错误 */
    BAD_REQUEST("BAD_REQUEST", "请求参数错误"),

    /** 参数校验失败 */
    VALIDATION_FAILED("VALIDATION_FAILED", "参数校验失败"),

    /** 未认证，需要登录 */
    UNAUTHORIZED("UNAUTHORIZED", "请先登录后再访问"),

    /** 无权限访问 */
    FORBIDDEN("FORBIDDEN", "无权限访问该资源"),

    /** 资源不存在 */
    NOT_FOUND("NOT_FOUND", "请求的资源不存在"),

    /** 资源状态冲突 */
    CONFLICT("CONFLICT", "资源状态冲突"),

    /** 业务逻辑错误 */
    BUSINESS_ERROR("BUSINESS_ERROR", "业务处理失败"),

    /** 服务内部错误 */
    INTERNAL_ERROR("INTERNAL_ERROR", "服务内部错误");

    /** 状态码 */
    private final String code;

    /** 状态码对应的消息描述 */
    private final String message;

    /**
     * 构造函数
     *
     * @param code    状态码
     * @param message 消息描述
     */
    ResponseCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
