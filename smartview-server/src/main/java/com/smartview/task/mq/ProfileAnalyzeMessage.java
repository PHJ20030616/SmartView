package com.smartview.task.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 画像分析任务消息。
 *
 * 分析所需内容由 FastAPI 根据 resumeProfileId、profileVersion、roleDirection
 * 从 MySQL 和 Chroma 读取，避免把完整简历放进 MQ，也避免前端伪造用户隔离字段。
 *
 * vectorizeCompleted 是 Spring 侧校验简历向量已成功入库后的结果标记：
 * FastAPI worker 消费时若发现为 false，应作为确定性业务错误立即回传失败，
 * 避免继续执行没有向量上下文的分析。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileAnalyzeMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;
    private LocalDateTime createdAt;

    /**
     * 简历画像 ID，FastAPI 据此从 MySQL 读取已确认画像
     */
    private String resumeProfileId;

    /**
     * 面试方向：JAVA_BACKEND / AGENT_DEVELOPMENT
     */
    private String roleDirection;

    /**
     * 简历画像版本号，确保使用正确版本的简历数据
     */
    private Integer profileVersion;

    /**
     * 简历向量是否已成功入库；任务投递前必须校验为 true
     */
    private Boolean vectorizeCompleted;
}
