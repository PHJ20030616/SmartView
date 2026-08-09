package com.smartview.ai.client;

import lombok.Data;

import java.util.List;

/**
 * FastAPI 回答评估请求模型（Spring Boot → FastAPI）。
 *
 * 功能说明：
 * - 对应 ai-api 契约的 EvaluateAnswerRequest，字段一一对应（camelCase）
 * - 由 AiInterviewClient POST 到 FastAPI /api/v1/interview/evaluate
 * - questionText/expectedPoints 是评估对照依据；stagePlan 为 Spring 端维护的
 *   阶段计划 JSON（ObjectMapper 解析为 JsonNode 后透传），供追问深度门控使用
 *
 * @author SmartView Team
 * @since 2026-08-09
 */
@Data
public class AiEvaluateAnswerRequest {

    /** 面试会话 ID */
    private String sessionId;

    /** 问题 ID */
    private String questionId;

    /** 用户回答文本 */
    private String answerText;

    /** 面试方向 */
    private String roleDirection;

    /** 当前问题正文（评估对照依据之一） */
    private String questionText;

    /** 期望回答要点（评估对照依据） */
    private List<String> expectedPoints;

    /** 阶段计划（不透明对象） */
    private Object stagePlan;

    /** 会话上下文：当前阶段/主题、已提问数量与覆盖度 */
    private SessionContext sessionContext;

    /** 链路追踪 ID */
    private String traceId;

    /**
     * 会话上下文。
     */
    @Data
    public static class SessionContext {

        /** 当前阶段：BASIC / PROJECT / SCENARIO */
        private String currentStage;

        /** 当前主题 */
        private String currentTopic;

        /** 已提问数量 */
        private Integer questionCount;

        /** 阶段覆盖度（不透明对象） */
        private Object stageCoverage;
    }
}
