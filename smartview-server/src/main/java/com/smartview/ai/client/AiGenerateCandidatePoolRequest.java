package com.smartview.ai.client;

import lombok.Data;

import java.util.List;

/**
 * FastAPI 候选池生成请求模型（Spring Boot → FastAPI）。
 *
 * 功能说明：
 * - 对应 ai-api 契约的 GenerateCandidatePoolRequest，字段一一对应（camelCase）
 * - 由 AiInterviewClient POST 到 FastAPI /api/v1/interview/candidate-pool
 * - stagePlan / stageCoverage 为 Spring 端维护的 JSON，由 ObjectMapper 解析为
 *   JsonNode 后透传（不透明 payload），FastAPI 按 docs/interview-policy.md 2.2/2.3 解析
 *
 * @author SmartView Team
 * @since 2026-08-07
 */
@Data
public class AiGenerateCandidatePoolRequest {

    /** 面试会话 ID */
    private String sessionId;

    /** 当前问题 ID，候选池归属的题目 */
    private String questionId;

    /** 面试方向 */
    private String roleDirection;

    /** 候选池类型：PRE_GENERATED 预生成 / FOLLOW_UP 追问 */
    private String poolType;

    /** 当前阶段：BASIC / PROJECT / SCENARIO */
    private String currentStage;

    /** 阶段计划（不透明对象） */
    private Object stagePlan;

    /** 阶段覆盖度（不透明对象） */
    private Object stageCoverage;

    /** 会话上下文：当前主题与已提问数量 */
    private SessionContext sessionContext;

    /** 回答评估事实（仅 FOLLOW_UP 池需要） */
    private EvaluationFacts evaluationFacts;

    /** 最近已问主题，用于生成时避免重复 */
    private List<String> historyTopics;

    /** 链路追踪 ID */
    private String traceId;

    /**
     * 会话上下文。
     */
    @Data
    public static class SessionContext {

        /** 当前主题 */
        private String currentTopic;

        /** 已提问数量 */
        private Integer questionCount;
    }

    /**
     * 回答评估事实。
     */
    @Data
    public static class EvaluationFacts {

        /** 回答得分 0-100 */
        private Integer score;

        /** 回答等级：GOOD / NORMAL / WEAK */
        private String level;

        /** 已命中要点 */
        private List<String> matchedPoints;

        /** 缺失要点 */
        private List<String> missingPoints;

        /** 风险点（对象数组，含 category/description） */
        private List<Object> riskPoints;

        /** 用户回答文本 */
        private String answerText;

        /** 当前问题正文 */
        private String questionText;
    }
}
