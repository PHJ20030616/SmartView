package com.smartview.ai.client;

import lombok.Data;

import java.util.List;

/**
 * FastAPI 首题生成响应模型（FastAPI → Spring Boot）。
 *
 * 功能说明：
 * - 对应 ai-api 契约的 QuestionResponse，字段一一对应（camelCase）
 * - 由 RestTemplate + Jackson 反序列化，Spring 侧据此回写 interview_question
 *
 * 业务语义：
 * - success=false 时，其余业务字段可能为空，需读取 errorMessage 提示用户
 * - questionType / sourceType / topic 为契约枚举值，Spring 端需做合法性兜底
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Data
public class AiFirstQuestionResponse {

    /** 生成是否成功 */
    private Boolean success;

    /** 问题正文 */
    private String questionText;

    /** 问题主题，回写 interview_question.topic */
    private String topic;

    /** 问题类型：OPENING / FOLLOW_UP / SWITCH_TOPIC / STAGE_ENTRY */
    private String questionType;

    /** 来源类型：KNOWLEDGE_BASE / EXPERIENCE_CASE / RESUME_PROJECT / MIXED */
    private String sourceType;

    /** 期望回答要点 */
    private List<String> expectedPoints;

    /** 引用的八股知识片段 */
    private List<KnowledgeRef> knowledgeRefs;

    /** 引用的面经案例 */
    private List<CaseRef> caseRefs;

    /** 生成失败原因 */
    private String errorMessage;

    /**
     * 引用的八股知识片段。
     */
    @Data
    public static class KnowledgeRef {

        /** 知识片段标题 */
        private String title;

        /** 知识分类 */
        private String category;

        /** 知识片段摘要 */
        private String snippet;
    }

    /**
     * 引用的面经案例。
     */
    @Data
    public static class CaseRef {

        /** 案例标题 */
        private String title;

        /** 案例场景 */
        private String scenario;

        /** 案例摘要 */
        private String snippet;
    }
}
