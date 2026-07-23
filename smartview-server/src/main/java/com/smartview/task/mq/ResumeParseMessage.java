package com.smartview.task.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历解析任务 MQ 消息实体
 *
 * 功能说明：
 * - 封装发送到 RabbitMQ 的简历解析任务消息
 * - 严格遵循 contracts/mq/resume_parse_task.schema.json 契约定义
 * - 由 Spring Boot 发送，FastAPI AI 服务消费
 *
 * 契约版本：1.0.0
 * 消息类型：RESUME_PARSE_TASK
 * 路由键：resume.parse.task
 * 队列名：smartview.resume.parse
 *
 * 字段说明：
 * - taskId：任务唯一标识（UUID），对应 ai_task.task_id
 * - traceId：链路追踪 ID（UUID），用于分布式追踪
 * - messageType：消息类型，固定为 "RESUME_PARSE_TASK"
 * - schemaVersion：消息 schema 版本号，固定为 "1.0.0"
 * - retryCount：当前重试次数，初始为 0，消费失败后递增
 * - createdAt：消息创建时间
 * - fileUrl：MinIO 预签名 URL，AI 服务通过此 URL 下载文件
 * - mimeType：文件 MIME 类型，例如 "application/pdf"
 * - resumeFileId：简历文件 ID，对应 resume_file.id
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeParseMessage {

    /**
     * 任务唯一标识（UUID）
     * 对应 ai_task.task_id
     * 用于跨服务追踪任务状态
     */
    private String taskId;

    /**
     * 链路追踪 ID（UUID）
     * 用于分布式链路追踪（SkyWalking、Zipkin、Jaeger）
     * 从 Spring Boot 传递到 FastAPI，实现全链路追踪
     */
    private String traceId;

    /**
     * 消息类型
     * 固定为 "RESUME_PARSE_TASK"
     * 用于消费者路由和识别
     */
    private String messageType;

    /**
     * 消息 schema 版本号
     * 固定为 "1.0.0"
     * 用于消息格式的版本管理，支持平滑升级
     */
    private String schemaVersion;

    /**
     * 当前重试次数
     * 初始为 0，消费失败后递增
     * 最大值为 3（对应 schema 定义的 maximum: 3）
     */
    private Integer retryCount;

    /**
     * 消息创建时间
     * ISO 8601 格式：2026-07-23T10:30:00+08:00
     * 用于监控消息时效性和排查延迟问题
     */
    private LocalDateTime createdAt;

    /**
     * MinIO 预签名下载 URL
     * 有效期 1 小时，AI 服务通过此 URL 下载文件进行解析
     * 格式示例：http://localhost:9000/smartview/resumes/1/abc-123.pdf?X-Amz-Algorithm=...
     */
    private String fileUrl;

    /**
     * 文件 MIME 类型
     * 当前仅支持 "application/pdf"
     * 后续可扩展支持 docx、doc、图片等格式
     */
    private String mimeType;

    /**
     * 简历文件 ID
     * 对应 resume_file.id（数据库主键）
     * AI 服务解析完成后，通过此 ID 回调更新解析状态
     */
    private String resumeFileId;
}
