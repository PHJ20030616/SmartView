package com.smartview.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 解析状态枚举
 *
 * 用于标识简历文件的AI解析状态，贯穿整个解析生命周期
 *
 * 状态流转：
 * PENDING → PROCESSING → SUCCESS
 *                       → FAILED
 *
 * 业务规则：
 * - 文件上传后初始状态为 PENDING
 * - AI 服务开始处理时更新为 PROCESSING
 * - 解析成功后更新为 SUCCESS，同时创建 ResumeProfile 记录
 * - 解析失败后更新为 FAILED，记录错误信息到 error_message
 * - FAILED 状态的文件支持用户手动重试，重试时状态重置为 PENDING
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Getter
@AllArgsConstructor
public enum ParseStatus {

    /**
     * 待解析
     * 文件已上传到 MinIO，但尚未触发 AI 解析任务
     */
    PENDING("PENDING", "待解析"),

    /**
     * 解析中
     * AI 服务已接收任务，正在提取文本并结构化解析
     */
    PROCESSING("PROCESSING", "解析中"),

    /**
     * 解析成功
     * AI 已生成结构化简历画像，数据已保存到 resume_profile 表
     */
    SUCCESS("SUCCESS", "解析成功"),

    /**
     * 解析失败
     * AI 服务返回错误，或处理超时，错误信息已记录到 error_message
     */
    FAILED("FAILED", "解析失败");

    /**
     * 状态代码，与数据库字段值一致
     */
    private final String code;

    /**
     * 状态描述，用于前端展示
     */
    private final String description;

    /**
     * 根据状态代码获取枚举实例
     *
     * @param code 状态代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static ParseStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ParseStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
