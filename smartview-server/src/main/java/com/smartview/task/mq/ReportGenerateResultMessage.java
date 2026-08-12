package com.smartview.task.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FastAPI 返回的报告生成结果消息。
 *
 * envelope 字段（taskId/sessionId/success 等）用于任务关联与终态更新；
 * 内容字段（overallScore/referenceAnswers 等）使用 JsonNode 承接 FastAPI 返回的
 * JSON 结构，业务层负责序列化为字符串写入 interview_report / reference_answer 表，
 * 避免手写与契约重复的跨端 DTO（契约优先规则）。success=false 时内容字段为空。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportGenerateResultMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;
    private String createdAt;
    private String sessionId;
    private Boolean success;

    /** 生成的报告 ID（interview_report.id） */
    private String reportId;

    /** 综合得分 0-100 */
    private Integer overallScore;

    /** 面试准备度等级：NOT_READY/NEEDS_PRACTICE/READY/WELL_PREPARED */
    private String readinessLevel;

    /** 岗位匹配度得分 0-100 */
    private Integer roleFitScore;

    /** 总体评价 */
    private String summary;

    /** 优势点 JSON 数组 */
    private JsonNode strengths;

    /** 薄弱点 JSON 数组 */
    private JsonNode weaknesses;

    /** 风险点 JSON 数组 */
    private JsonNode riskPoints;

    /** 学习建议 JSON 对象数组 [{topic, reason, resources}] */
    private JsonNode suggestions;

    /** 覆盖情况 JSON 对象 {basicCoverage, projectCoverage, scenarioCoverage} */
    private JsonNode coverage;

    /** 每题参考答案 JSON 数组 [{questionId, answerType, referenceContent, keyPoints, tradeoffs}] */
    private JsonNode referenceAnswers;

    /** 生成失败原因，success=false 时必需 */
    private String errorMessage;
}
