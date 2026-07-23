package com.smartview.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 任务状态枚举
 *
 * 用于标识异步 AI 任务的执行状态，支持任务重试机制
 *
 * 状态流转：
 * PENDING → PROCESSING → SUCCESS
 *                       → FAILED (retry_count >= max_retry)
 *                       → RETRYING (retry_count < max_retry) → PROCESSING → ...
 *
 * 业务规则：
 * - 任务创建后初始状态为 PENDING
 * - AI 服务开始处理时更新为 PROCESSING
 * - 处理成功后更新为 SUCCESS，保存结果到 result_payload_json
 * - 处理失败且未达到最大重试次数时更新为 RETRYING，准备重试
 * - 处理失败且达到最大重试次数时更新为 FAILED，标记为最终失败
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Getter
@AllArgsConstructor
public enum TaskStatus {

    /**
     * 待处理
     * 任务已创建，MQ 消息已发送，等待 AI 服务消费
     */
    PENDING("PENDING", "待处理"),

    /**
     * 处理中
     * AI 服务已接收任务，正在执行
     */
    PROCESSING("PROCESSING", "处理中"),

    /**
     * 成功
     * 任务执行完成，结果已保存
     */
    SUCCESS("SUCCESS", "成功"),

    /**
     * 失败
     * 任务执行失败，且已达到最大重试次数
     */
    FAILED("FAILED", "失败"),

    /**
     * 重试中
     * 任务执行失败，但未达到最大重试次数，准备重试
     */
    RETRYING("RETRYING", "重试中");

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
    public static TaskStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (TaskStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
