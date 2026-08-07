package com.smartview.interview.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 候选问题池中的一道候选题。
 *
 * 功能说明：
 * - 候选池只提供备选问题，不决定下一步；最终动作由 StagePolicyEngine（Task 5.4）决定
 * - 供 AiInterviewClient 响应解析、Redis 存储（Jackson JSON）、FollowUpPoolService 复用，
 *   避免为同一结构维护多份重复 DTO
 *
 * 字段说明：
 * - candidateType：SAME_STAGE_SWITCH 同阶段换题 / NEXT_STAGE_ENTRY 下一阶段入口 / FOLLOW_UP 追问
 * - targetPoint：目标考察点（追问时即追问依据）
 * - reason：生成原因，追问场景说明基于哪个缺失点/风险点/亮点
 *
 * @author SmartView Team
 * @since 2026-08-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidatePoolItem {

    /** 候选问题正文 */
    private String questionText;

    /** 问题主题 */
    private String topic;

    /** 所属阶段：BASIC / PROJECT / SCENARIO */
    private String stage;

    /** 候选类型：SAME_STAGE_SWITCH / NEXT_STAGE_ENTRY / FOLLOW_UP */
    private String candidateType;

    /** 来源类型：KNOWLEDGE_BASE / EXPERIENCE_CASE / RESUME_PROJECT / MIXED */
    private String sourceType;

    /** 期望回答要点 */
    private List<String> expectedPoints;

    /** 目标考察点 */
    private String targetPoint;

    /** 生成原因 */
    private String reason;
}
