package com.smartview.task.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历向量入库任务消息。
 *
 * 画像内容由 FastAPI 根据 resumeProfileId 从 MySQL 读取，避免把完整简历
 * 直接放进 MQ，也避免前端能够伪造用户隔离字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeVectorizeMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;
    private LocalDateTime createdAt;
    private String resumeProfileId;
    private Integer profileVersion;

    /**
     * 向量操作类型；历史消息缺失时由 FastAPI 按 UPSERT 兼容处理。
     */
    @Builder.Default
    private String operation = "UPSERT";
}
