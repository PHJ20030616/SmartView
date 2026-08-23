package com.smartview.cleanup;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 清理任务结果消息（CLEANUP_RESULT）。
 *
 * <p>与 contracts/mq/cleanup_result.schema.json 保持一致。FastAPI cleanup worker
 * 执行完物理清理后回传，Spring 据此更新 ai_task 为 SUCCESS/FAILED 并保存
 * result_payload_json 作为审计信息。</p>
 *
 * <p>createdAt 沿用结果消息的 String 约定（见 task/mq 下各 *ResultMessage），
 * 直接透传 worker 的 RFC 3339 时间字符串，满足契约 format: date-time 校验
 * （LocalDateTime 序列化无时区偏移，会违反 RFC 3339）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CleanupResultMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;

    /**
     * 消息创建时间（RFC 3339 字符串，直接透传，不做时区转换）。
     */
    private String createdAt;

    /**
     * 关联业务类型，与任务消息一致。
     */
    private String bizType;

    /**
     * 关联业务 ID，与任务消息一致。
     */
    private String bizId;

    /**
     * 是否清理成功；失败时 errorMessage 必填。
     */
    private Boolean success;

    /**
     * 成功清理的画像数量（含按幂等视为已清理的画像），用于审计。
     */
    private Integer cleanedProfileCount;

    /**
     * 失败原因；success=false 时必填。
     */
    private String errorMessage;
}
