package com.smartview.interview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户回答实体类
 *
 * 功能说明：
 * - 映射数据库 interview_answer 表
 * - 保存用户对每个问题的回答，一个问题最多一份有效回答
 *
 * 关键设计：
 * 1. 唯一索引 (question_id, deleted) 保证同一问题最多一份有效回答（deleted=0），
 *    同时兼容软删除：允许"有效回答→软删除→重建有效回答"；
 * 2. requestId 为回答提交幂等 ID（可来自 Idempotency-Key 请求头），
 *    配合 interview_session.version 乐观锁，防止重复/并发提交生成多道下一题；
 * 3. answerMode 第一版主要为 TEXT，后续可扩展 VOICE_TO_TEXT；
 * 4. 软删除：deleted 字段配合 @TableLogic 实现逻辑删除。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_answer")
public class InterviewAnswer {

    /**
     * 回答ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属面试会话ID，外键关联 interview_session 表
     */
    private Long sessionId;

    /**
     * 对应问题ID，外键关联 interview_question 表
     */
    private Long questionId;

    /**
     * 所属用户ID，外键关联 user 表，用于权限控制和用户维度查询
     */
    private Long userId;

    /**
     * 用户回答文本
     */
    private String answerText;

    /**
     * 回答方式：TEXT=文本，后续可扩展 VOICE_TO_TEXT=语音转文本
     */
    private String answerMode;

    /**
     * 用户作答耗时，单位秒
     */
    private Integer durationSeconds;

    /**
     * 回答提交幂等ID，可来自 Idempotency-Key 请求头，
     * 防止重复提交生成多道下一题
     */
    private String requestId;

    /**
     * 提交时间
     */
    private LocalDateTime submittedAt;

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
