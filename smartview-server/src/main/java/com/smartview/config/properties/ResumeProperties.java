package com.smartview.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 简历上传配置属性类
 *
 * 功能说明：
 * - 从 application.yml 读取 smartview.resume 配置项
 * - 提供简历上传的各项限制和重试配置
 * - 支持运维通过配置文件动态调整，无需修改代码
 *
 * 配置项：
 * - maxFileSize：文件大小限制（字节），默认 10MB
 * - allowedMimeTypes：允许的 MIME 类型，默认仅支持 application/pdf
 * - mq.maxRetryAttempts：MQ 投递失败时的立即重试次数
 * - mq.retryBaseDelayMs：重试基础延迟（毫秒），使用指数退避策略
 * - mq.scheduledRetryIntervalMinutes：定时任务扫描失败任务的间隔
 * - mq.maxScheduledRetryCount：定时任务最大重试次数
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Data
@Component
@ConfigurationProperties(prefix = "smartview.resume")
public class ResumeProperties {

    /**
     * 文件大小限制，单位字节
     * 默认 10MB（10 * 1024 * 1024）
     * 建议根据实际业务场景调整，避免过大文件占用存储和处理资源
     */
    private Long maxFileSize = 10485760L;

    /**
     * 允许的 MIME 类型列表
     * 默认仅支持 PDF 格式
     * 后续可扩展支持 DOCX、DOC、图片等格式
     */
    private String allowedMimeTypes = "application/pdf";

    /**
     * MQ 相关配置
     */
    private MqConfig mq = new MqConfig();

    /**
     * MQ 配置内部类
     */
    @Data
    public static class MqConfig {
        /**
         * 上传接口中 MQ 投递失败时的立即重试次数
         * 默认 3 次，使用指数退避策略（100ms -> 300ms -> 900ms）
         * 用于处理瞬时网络抖动，快速恢复
         */
        private Integer maxRetryAttempts = 3;

        /**
         * 重试基础延迟（毫秒）
         * 实际延迟 = retryBaseDelayMs * 3^(attempt-1)
         * 默认 100ms，第一次重试 100ms，第二次 300ms，第三次 900ms
         */
        private Long retryBaseDelayMs = 100L;

        /**
         * 定时任务扫描失败任务的间隔（分钟）
         * 默认 5 分钟扫描一次 FAILED 状态的任务
         * 用于兜底处理立即重试仍失败的情况
         */
        private Integer scheduledRetryIntervalMinutes = 5;

        /**
         * 定时任务最大重试次数
         * 默认 3 次，超过后标记为 PERMANENTLY_FAILED
         * 避免无效任务反复重试浪费资源
         */
        private Integer maxScheduledRetryCount = 3;
    }
}
