package com.smartview.interview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试问题实体类
 *
 * 功能说明：
 * - 映射数据库 interview_question 表
 * - 保存系统提出的每一道题（仅保存真正提问过的问题，候选池题目存在 Redis）
 * - 通过 parentQuestionId 表达追问关系，形成树形结构
 *
 * 关键设计：
 * 1. 同一会话内 questionOrder 唯一，配合唯一索引 (session_id, question_order)；
 * 2. 引用信息以 JSON 存储：knowledgeRefsJson（八股知识）、caseRefsJson（面经案例）、
 *    expectedPointsJson（期望回答要点），用于回答评估与复盘溯源；
 * 3. 来源类型 sourceType 区分题目出处（知识库/面经/简历项目/混合）；
 * 4. 软删除：deleted 字段配合 @TableLogic 实现逻辑删除。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_question")
public class InterviewQuestion {

    /**
     * 问题ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属面试会话ID，外键关联 interview_session 表
     */
    private Long sessionId;

    /**
     * 所属用户ID，外键关联 user 表，用于权限控制和用户维度查询
     */
    private Long userId;

    /**
     * 当前会话中的问题序号，从 1 递增，同一会话内唯一
     */
    private Integer questionOrder;

    /**
     * 父问题ID，用于表示追问关系（如 OPENING 题的 FOLLOW_UP 追问），
     * 可为空：第一题为空，其余追问指向其父问题
     */
    private Long parentQuestionId;

    /**
     * 所属阶段：BASIC=基础八股，PROJECT=项目追问，SCENARIO=场景题
     */
    private String stage;

    /**
     * 问题类型：OPENING=开场题，FOLLOW_UP=追问，SWITCH_TOPIC=换题，STAGE_ENTRY=阶段入口题
     */
    private String questionType;

    /**
     * 问题主题，例如某个技术点、某个项目、某类场景
     */
    private String topic;

    /**
     * 问题正文
     */
    private String questionText;

    /**
     * 来源类型：KNOWLEDGE_BASE=八股知识库，EXPERIENCE_CASE=面经案例，
     * RESUME_PROJECT=简历项目，MIXED=混合来源
     */
    private String sourceType;

    /**
     * 引用的八股知识片段信息 JSON，用于溯源与复盘
     */
    @TableField("knowledge_refs_json")
    private String knowledgeRefsJson;

    /**
     * 引用的面经案例信息 JSON
     */
    @TableField("case_refs_json")
    private String caseRefsJson;

    /**
     * 期望回答要点 JSON，作为回答评估的对照依据
     */
    @TableField("expected_points_json")
    private String expectedPointsJson;

    /**
     * 问题状态：ASKED=已提问，ANSWERED=已回答，SKIPPED=已跳过
     */
    private String status;

    /**
     * 提问时间
     */
    private LocalDateTime askedAt;

    /**
     * 记录创建时间，由 MyMetaObjectHandler 在插入时自动填充
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间，由 MyMetaObjectHandler 在插入和更新时自动填充
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 软删除标记：0=未删除，1=已删除，配合 @TableLogic 实现逻辑删除
     */
    @TableLogic
    private Integer deleted;
}
