package com.smartview.task.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FastAPI 返回的简历向量入库结果消息。
 *
 * profileVersion 是结果关联的必要条件，消费者不能只按 profileId 更新任务，
 * 否则旧版本任务迟到时会污染新版本状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeVectorizeResultMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;
    private String createdAt;
    private String resumeProfileId;
    private Integer profileVersion;
    /**
     * FastAPI 实际执行的操作类型；缺失时兼容为 UPSERT。
     */
    @Builder.Default
    private String operation = "UPSERT";
    private Boolean success;
    private Integer chunksCount;
    private String errorMessage;
}
