package com.smartview.ai.client;

import lombok.Data;

/**
 * FastAPI 首题生成请求模型（Spring Boot → FastAPI）。
 *
 * 功能说明：
 * - 对应 ai-api 契约的 GenerateFirstQuestionRequest，字段一一对应（camelCase）
 * - 请求体由 Jackson 序列化后经 AiInterviewClient POST 到 FastAPI，
 *   并通过 X-API-Key 请求头做跨服务鉴权
 *
 * 字段说明：
 * - stagePlan：Spring 生成的 stage_plan_json（snake_case 对象），由 ObjectMapper
 *   解析为 JsonNode 后透传，FastAPI 按 docs/interview-policy.md 2.2 结构解析
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Data
public class AiFirstQuestionRequest {

    /** 面试会话 ID */
    private String sessionId;

    /** 面试方向，用于 FastAPI 知识库/面经检索过滤与出题上下文 */
    private String roleDirection;

    /** 阶段计划（不透明对象，Spring 端生成后原样透传） */
    private Object stagePlan;

    /** 简历画像 ID */
    private String resumeProfileId;

    /** 简历画像版本号 */
    private Integer profileVersion;

    /** 链路追踪 ID */
    private String traceId;
}
