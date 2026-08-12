package com.smartview.task.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 报告生成任务消息。
 *
 * 只携带 sessionId：报告所需的会话、题目、回答、评估、画像等数据由 FastAPI
 * 从 MySQL 读取，避免把完整上下文塞进 MQ，也避免前端伪造用户隔离字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportGenerateMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;
    private LocalDateTime createdAt;

    /** 面试会话 ID，FastAPI 据此读取会话与问答评估数据 */
    private String sessionId;
}
