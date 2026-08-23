package com.smartview.cleanup;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 清理任务消息（CLEANUP_TASK）。
 *
 * <p>与 contracts/mq/cleanup_task.schema.json 保持一致。消息不携带完整简历内容，
 * 只携带清理目标：MinIO 对象 Key 与需删除 Chroma 向量的简历画像 ID 列表。
 * 画像已软删除后仍可依据 ID 幂等删除向量，因此清理任务不依赖业务行存活。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CleanupMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;
    private LocalDateTime createdAt;

    /**
     * 关联业务类型，当前仅支持 RESUME_FILE（简历文件）。
     */
    private String bizType;

    /**
     * 关联业务 ID，bizType=RESUME_FILE 时为 resume_file.id。
     */
    private String bizId;

    /**
     * MinIO 对象 Key；对象不存在时 worker 按已清理处理（幂等）。
     */
    private String objectKey;

    /**
     * 需清理 Chroma 向量的简历画像 ID 列表。
     */
    private List<String> resumeProfileIds;
}
