package com.smartview.task.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 简历解析结果 MQ 消息实体
 *
 * 功能说明：
 * - 封装从 RabbitMQ 接收的简历解析结果消息
 * - 严格遵循 contracts/mq/resume_parse_result.schema.json 契约定义
 * - 由 FastAPI AI 服务发送，Spring Boot 消费
 *
 * 契约版本：1.0.0
 * 消息类型：RESUME_PARSE_RESULT
 * 路由键：resume.parse.result
 * 队列名：smartview.resume.parse.result.v1
 *
 * 消息结构规则：
 * - success=true 时必须携带解析出的结构化字段（candidateName、rawText 等）
 * - success=false 时必须携带 errorMessage 说明失败原因
 * - 各数组字段默认初始化为空列表，避免空指针
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeParseResultMessage {

    /**
     * 任务唯一标识（UUID）
     * 对应 ai_task.task_id，用于关联任务和防重
     */
    private String taskId;

    /**
     * 链路追踪 ID（UUID）
     * 用于分布式链路追踪，跨服务保持一致
     */
    private String traceId;

    /**
     * 消息类型
     * 固定为 "RESUME_PARSE_RESULT"，用于消费者识别
     */
    private String messageType;

    /**
     * 消息 schema 版本号
     * 固定为 "1.0.0"，用于消息格式版本管理
     */
    private String schemaVersion;

    /**
     * 当前重试次数
     * 范围 0-3，首次投递为 0
     */
    private Integer retryCount;

    /**
     * 消息创建时间
     * ISO 8601 格式，String 类型避免 Jackson 时区反序列化异常
     * Python Pydantic 输出可能带时区偏移（Z 或 +08:00），String 能够原样接收
     * 示例：2026-07-25T10:30:00+08:00 或 2026-07-25T10:30:00Z
     */
    private String createdAt;

    /**
     * 简历文件 ID
     * 对应 resume_file.id（数据库主键），用于关联业务数据
     */
    private String resumeFileId;

    /**
     * 解析是否成功
     * true=解析成功，结构化数据可用；false=解析失败，需读取 errorMessage
     */
    private Boolean success;

    /**
     * 候选人姓名
     * 从简历中提取的姓名，仅在 success=true 时有值
     */
    private String candidateName;

    /**
     * 联系方式（JSON 对象）
     * 包含 phone、email、location 等字段，仅在 success=true 时有值
     * 示例：{"phone": "13800138000", "email": "zhangsan@example.com"}
     */
    private Map<String, Object> contactInfo;

    /**
     * 教育经历列表
     * 每项包含 school、degree、major、startDate、endDate，仅在 success=true 时有值
     */
    private List<Map<String, Object>> education;

    /**
     * 工作经历列表
     * 每项包含 company、position、startDate、endDate、description，仅在 success=true 时有值
     */
    private List<Map<String, Object>> workExperience;

    /**
     * 项目经历列表
     * 每项包含 projectName、role、description、techStack，仅在 success=true 时有值
     */
    @JsonProperty("projectExperience")
    private List<Map<String, Object>> projectExperience;

    /**
     * 技能列表
     * 字符串数组，例如 ["Java", "Spring Boot", "MySQL"]，仅在 success=true 时有值
     */
    private List<String> skills;

    /**
     * 简历原文
     * PDF 提取或 OCR 得到的纯文本内容，仅在 success=true 时必须有值
     */
    private String rawText;

    /**
     * 解析失败原因
     * 仅在 success=false 时必须有值，例如 "PDF 文件损坏，无法提取文本"
     */
    private String errorMessage;
}
