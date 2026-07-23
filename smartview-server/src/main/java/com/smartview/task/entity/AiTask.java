package com.smartview.task.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 任务实体类
 *
 * 功能说明：
 * - 映射数据库 ai_task 表
 * - 统一管理所有异步 AI 任务的生命周期（简历解析、画像分析、报告生成等）
 * - 支持任务重试机制（记录重试次数和最大重试次数）
 * - 支持分布式链路追踪（trace_id）
 * - 支持 MQ 消息管理（message_type、schema_version）
 * - 支持软删除（deleted 字段配合 @TableLogic）
 * - 支持字段自动填充（createdAt 和 updatedAt 配合 MyMetaObjectHandler）
 *
 * 技术要点：
 * - @TableName("ai_task")：映射到 ai_task 表
 * - @TableId(type = IdType.AUTO)：主键自增策略
 * - @TableLogic：标记 deleted 字段为逻辑删除字段（0=未删除，1=已删除）
 * - @TableField(fill = FieldFill.INSERT)：插入时自动填充 createdAt
 * - @TableField(fill = FieldFill.INSERT_UPDATE)：插入和更新时自动填充 updatedAt
 *
 * 业务流程：
 * 1. 创建任务：Spring Boot 创建 AiTask 记录（task_status=PENDING）
 * 2. 发送 MQ 消息：投递到 RabbitMQ，由 FastAPI AI 服务消费
 * 3. 开始处理：AI 服务更新状态为 PROCESSING，记录 started_at
 * 4. 处理完成：
 *    - 成功：更新 task_status=SUCCESS，保存 result_payload_json，记录 finished_at
 *    - 失败：更新 task_status=FAILED，记录 error_message，重试次数未达上限则 task_status=RETRYING
 * 5. 重试机制：retry_count < max_retry 时自动重试，否则最终标记为 FAILED
 *
 * 任务类型（task_type）：
 * - RESUME_PARSE：简历解析（从 PDF 提取文本并结构化）
 * - PROFILE_ANALYZE：画像分析（基于简历画像生成能力评估）
 * - REPORT_GENERATE：报告生成（生成面试报告、匹配度报告等）
 * - CLEANUP：清理任务（定期清理过期数据）
 *
 * 任务状态（task_status）：
 * - PENDING：待处理（任务已创建，等待消费）
 * - PROCESSING：处理中（AI 服务正在处理）
 * - SUCCESS：成功（任务完成，结果已保存）
 * - FAILED：失败（达到最大重试次数，最终失败）
 * - RETRYING：重试中（失败但未达最大重试次数，准备重试）
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ai_task")
public class AiTask {

    /**
     * 主键ID，数据库自增
     * 仅用于数据库内部标识，业务层使用 task_id
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务任务ID，全局唯一字符串
     * 建议使用 UUID 或雪花算法生成，格式示例：
     * - UUID: "550e8400-e29b-41d4-a716-446655440000"
     * - 雪花ID: "1234567890123456789"
     *
     * 用途：
     * 1. 跨服务追踪任务状态（Spring Boot ← MQ → FastAPI）
     * 2. 幂等性保证：防止重复创建任务
     * 3. 分布式唯一标识：多实例部署时避免冲突
     */
    private String taskId;

    /**
     * 所属用户ID
     * 外键关联 user 表，用于：
     * 1. 权限控制：确保用户只能查看自己的任务
     * 2. 用户维度统计：统计每个用户的任务数、成功率
     * 3. 配额管理：限制单个用户的并发任务数或每日任务数
     */
    private Long userId;

    /**
     * 任务类型
     * 枚举值：
     * - RESUME_PARSE：简历解析
     * - PROFILE_ANALYZE：画像分析
     * - REPORT_GENERATE：报告生成
     * - CLEANUP：清理任务
     *
     * 不同任务类型可能对应不同的 AI 模型、处理流程、超时时间
     */
    private String taskType;

    /**
     * 任务状态
     * 枚举值：
     * - PENDING：待处理（任务已创建，MQ 消息已发送，等待 AI 服务消费）
     * - PROCESSING：处理中（AI 服务已接收任务，正在执行）
     * - SUCCESS：成功（任务完成，结果已保存到 result_payload_json）
     * - FAILED：失败（达到最大重试次数，最终失败，错误信息见 error_message）
     * - RETRYING：重试中（失败但未达最大重试次数，准备重试）
     *
     * 状态流转：
     * PENDING → PROCESSING → SUCCESS
     *                       → FAILED (retry_count >= max_retry)
     *                       → RETRYING (retry_count < max_retry) → PROCESSING → ...
     */
    private String taskStatus;

    /**
     * 关联业务类型
     * 标识任务关联的业务实体类型，用于反查业务数据
     * 枚举值：
     * - RESUME_FILE：简历文件（biz_id 指向 resume_file.id）
     * - INTERVIEW_SESSION：面试会话（biz_id 指向 interview_session.id，后续扩展）
     * - null：不关联具体业务实体的任务（如定期清理任务）
     */
    private String bizType;

    /**
     * 关联业务ID
     * 指向对应业务表的主键，配合 biz_type 使用
     * 示例：
     * - biz_type=RESUME_FILE, biz_id=123 → 指向 resume_file 表 id=123 的记录
     * - biz_type=INTERVIEW_SESSION, biz_id=456 → 指向 interview_session 表 id=456 的记录
     *
     * 用途：
     * 1. 业务关联：通过任务 ID 反查业务数据
     * 2. 状态同步：任务完成后更新业务表的状态
     */
    private Long bizId;

    /**
     * 投递给 AI 服务的请求 JSON
     * 记录发送给 FastAPI 的完整请求数据，用于：
     * 1. 审计追踪：记录每次任务的输入参数
     * 2. 重试：失败后可根据原始请求重新发起任务
     * 3. 调试：排查问题时对比请求和响应
     *
     * 示例（简历解析任务）：
     * {
     *   "resumeFileId": 123,
     *   "objectKey": "resumes/1/abc123.pdf",
     *   "mimeType": "application/pdf",
     *   "extractOptions": {
     *     "enableOcr": true,
     *     "language": "zh-CN"
     *   }
     * }
     */
    private String requestPayloadJson;

    /**
     * AI 服务返回的结果 JSON
     * 记录 FastAPI 返回的完整响应数据，用于：
     * 1. 结果查询：前端或业务层可直接读取任务结果
     * 2. 二次处理：基于结果进行后续业务逻辑
     * 3. 审计追踪：记录每次任务的输出
     *
     * 示例（简历解析任务）：
     * {
     *   "candidateName": "张三",
     *   "contactInfo": {...},
     *   "education": [...],
     *   "workExperience": [...],
     *   "skills": {...}
     * }
     */
    private String resultPayloadJson;

    /**
     * 失败原因
     * 记录任务失败时的错误信息，包括：
     * 1. AI 服务返回的错误消息
     * 2. 网络异常（超时、连接失败等）
     * 3. 业务校验失败（如文件损坏、格式不支持）
     *
     * 示例：
     * - "PDF 文件损坏，无法提取文本"
     * - "AI 服务超时（3000ms）"
     * - "简历内容不完整，缺少教育经历和工作经验"
     * - "网络异常：Connection refused"
     */
    private String errorMessage;

    /**
     * 当前重试次数
     * 每次重试递增，初始值为 0
     * 用于判断是否继续重试：retry_count < max_retry
     */
    private Integer retryCount;

    /**
     * 最大重试次数
     * 默认为 3，可根据任务类型动态调整：
     * - 简历解析：3 次（AI 服务偶尔不稳定）
     * - 清理任务：0 次（失败后无需重试）
     * - 报告生成：5 次（涉及多个 AI 模型调用，可能需要更多重试）
     */
    private Integer maxRetry;

    /**
     * 链路追踪ID
     * 用于分布式链路追踪（如 SkyWalking、Zipkin、Jaeger）
     * 格式示例：
     * - SkyWalking: "1.234.56789.1234567890.1234.1.1"
     * - Zipkin: "a1b2c3d4e5f6g7h8"
     *
     * 用途：
     * 1. 全链路追踪：跟踪请求从前端 → Spring Boot → MQ → FastAPI 的完整路径
     * 2. 性能分析：定位慢查询、慢接口
     * 3. 故障排查：快速定位问题发生的环节
     */
    private String traceId;

    /**
     * MQ 消息类型
     * 标识 RabbitMQ 消息的业务类型，用于消费者路由和处理
     * 示例：
     * - "resume.parse.request"
     * - "profile.analyze.request"
     * - "report.generate.request"
     *
     * 与 task_type 的区别：
     * - task_type：业务层面的任务分类
     * - message_type：消息层面的路由标识（对应 RabbitMQ 的 routing key）
     */
    private String messageType;

    /**
     * MQ 消息 schema 版本
     * 用于消息格式的版本管理，支持平滑升级
     * 示例：
     * - "v1.0"：初始版本
     * - "v1.1"：新增可选字段
     * - "v2.0"：破坏性变更（字段重命名、类型变更）
     *
     * 用途：
     * 1. 向后兼容：新版本消费者可处理旧版本消息
     * 2. 灰度发布：同时运行多个版本的消费者
     * 3. 契约测试：验证消息格式符合 schema 定义
     */
    private String schemaVersion;

    /**
     * 任务开始时间
     * AI 服务接收任务并开始处理的时间
     * 用于计算任务执行耗时：finished_at - started_at
     */
    private LocalDateTime startedAt;

    /**
     * 任务结束时间
     * 任务完成（成功或失败）的时间
     * 用于计算任务执行耗时：finished_at - started_at
     * 也用于统计任务平均耗时、P99 耗时等指标
     */
    private LocalDateTime finishedAt;

    /**
     * 记录创建时间
     * 由 MyMetaObjectHandler 在插入时自动填充，业务代码无需手动设置
     * 通常等于任务创建时间（用户触发操作的时间）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间
     * 由 MyMetaObjectHandler 在插入和更新时自动填充，业务代码无需手动设置
     * 每次状态变更（如 PENDING → PROCESSING → SUCCESS）都会自动更新此字段
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 软删除标记：0=未删除，1=已删除
     * 配合 @TableLogic 注解实现逻辑删除：
     * - 查询时自动添加 deleted=0 条件
     * - 调用 deleteById 时执行 UPDATE 而非 DELETE
     * - 历史任务可以软删除，只保留近期任务用于监控和统计
     */
    @TableLogic
    private Integer deleted;
}
