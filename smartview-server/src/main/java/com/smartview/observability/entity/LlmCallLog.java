package com.smartview.observability.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 调用日志实体，映射 llm_call_log 表。
 *
 * 关键设计：
 * 1. 这是技术可观测表，**写入方是 FastAPI**，Spring Boot 只读。
 *    因此这里刻意不加字段自动填充注解——本服务不会插入本表，
 *    created_at 由数据库的 DEFAULT CURRENT_TIMESTAMP 兜底。
 * 2. 没有 deleted 字段与 @TableLogic：调用日志只追加不修改，不做逻辑删除。
 * 3. 不保存 prompt 与响应全文，只有 request_hash 与 request_chars，
 *    避免把简历原文、用户回答等敏感数据扩散到观测表（plan_1.1 §5.1）。
 *
 * @author SmartView Team
 * @since 2026-09-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("llm_call_log")
public class LlmCallLog {

    /** 主键，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 链路追踪ID，与 Spring Boot 的 X-Trace-Id 关联 */
    private String traceId;

    /** 调用场景：question_generate / evaluate / report_generate / profile_analyze / resume_parse */
    private String scene;

    /** 业务对象类型（如 interview_session / resume_file / resume_profile），弱引用无外键 */
    private String bizType;

    /** 业务对象ID（弱引用，无外键约束，允许悬空） */
    private Long bizId;

    /** 模型提供方 */
    private String provider;

    /** 模型名称 */
    private String model;

    /** prompt 标识（如 report_generate.reference_answer），用于按提示词归因指标 */
    private String promptKey;

    /** prompt 版本，用于把指标变化归因到具体 prompt 迭代 */
    private String promptVersion;

    /** 提示词 sha256，不保存全文 */
    private String requestHash;

    /** 提示词字符数 */
    private Integer requestChars;

    /** 采样温度 */
    private BigDecimal temperature;

    /** 最大输出 token 数 */
    private Integer maxTokens;

    /** 输入 token 数 */
    private Integer tokenInput;

    /** 输出 token 数 */
    private Integer tokenOutput;

    /** 总 token 数 */
    private Integer tokenTotal;

    /** 调用耗时（毫秒） */
    private Integer latencyMs;

    /** 调用结果：SUCCESS / FAILED */
    private String status;

    /** 失败错误码 */
    private String errorCode;

    /** 失败错误信息（已截断） */
    private String errorMessage;

    /** 修复重试序号：0=首次调用，1=修复调用 */
    private Integer retryAttempt;

    /** 上游 HTTP 状态码；429=限流，5xx=上游故障，其余 4xx=请求被拒 */
    private Integer httpStatus;

    /** 上游停止原因；length=输出被 max_tokens 截断 */
    private String finishReason;

    /** MQ 任务重试轮次，0=首次处理（与 retryAttempt 是两个不同维度） */
    private Integer attemptNo;

    /** 记录创建时间 */
    private LocalDateTime createdAt;
}
