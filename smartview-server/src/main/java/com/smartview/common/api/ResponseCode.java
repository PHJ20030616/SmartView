package com.smartview.common.api;

public enum ResponseCode {
    SUCCESS("SUCCESS", "操作成功"),
    BAD_REQUEST("BAD_REQUEST", "请求参数错误"),
    VALIDATION_FAILED("VALIDATION_FAILED", "参数校验失败"),
    UNAUTHORIZED("UNAUTHORIZED", "请先登录后再访问"),
    FORBIDDEN("FORBIDDEN", "无权限访问该资源"),
    NOT_FOUND("NOT_FOUND", "请求的资源不存在"),
    BUSINESS_ERROR("BUSINESS_ERROR", "业务处理失败"),
    INTERNAL_ERROR("INTERNAL_ERROR", "服务内部错误");

    private final String code;
    private final String message;

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
