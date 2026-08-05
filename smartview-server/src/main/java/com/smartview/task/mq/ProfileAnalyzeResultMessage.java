package com.smartview.task.mq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * FastAPI 返回的画像分析结果消息。
 *
 * profileVersion 和 roleDirection 是结果关联的必要条件，消费者不能只按
 * profileId 更新，否则旧版本任务或错误方向的结果迟到时会污染新版本状态。
 *
 * 分析字段（skillTags / projectGraph 等）使用 JsonNode 承接 FastAPI 返回的
 * 任意 JSON 结构，业务层负责序列化为字符串写入 profile_analysis 表，避免
 * 手写与契约重复的跨端 DTO（契约优先规则）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileAnalyzeResultMessage {

    private String taskId;
    private String traceId;
    private String messageType;
    private String schemaVersion;
    private Integer retryCount;
    private String createdAt;
    private String resumeProfileId;
    private Integer profileVersion;
    private String roleDirection;
    private Boolean success;

    /**
     * 技能标签 JSON 数组，如 [{"skill":"Java","level":"EXPERT","source":"PROJECT"}]
     */
    private JsonNode skillTags;

    /**
     * 项目关系图谱 JSON 对象
     */
    private JsonNode projectGraph;

    /**
     * 能力线索 JSON 对象
     */
    private JsonNode capabilityHints;

    /**
     * 风险点 JSON 数组
     */
    private JsonNode riskPoints;

    /**
     * 建议面试主题 JSON 数组
     */
    private JsonNode suggestedTopics;

    /**
     * 阶段覆盖目标 JSON 对象
     */
    private JsonNode stageTargets;

    /**
     * 生成该分析结果使用的模型名称
     */
    private String modelName;

    /**
     * 模型版本或配置版本
     */
    private String modelVersion;

    /**
     * 分析失败原因，success=false 时必需
     */
    private String errorMessage;
}
