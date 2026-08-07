package com.smartview.interview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回答评估实体类
 *
 * 功能说明：
 * - 映射数据库 answer_evaluation 表
 * - 保存系统对某次回答的分析结果与下一步决策
 *
 * 关键设计：
 * 1. 一次回答对应一条评估记录，评估结果是后续追问/换题/切阶段/结束的决策输入，
 *    决策过程由 Task 5.4 的 StagePolicyEngine 完成，FastAPI 不直接返回决策；
 * 2. 各要点/风险以 JSON 存储：matchedPointsJson、missingPointsJson、riskPointsJson；
 * 3. candidatePoolSnapshotJson 记录本次决策使用的候选池快照，用于审计与复盘
 *    （interview-policy.md 9.1）；
 * 4. nextAction 为下一步动作，selectedNextQuestionId 为被选中的下一题（可为空）；
 * 5. 软删除：deleted 字段配合 @TableLogic 实现逻辑删除。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("answer_evaluation")
public class AnswerEvaluation {

    /**
     * 评估ID，主键，数据库自增
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
     * 对应回答ID，外键关联 interview_answer 表
     */
    private Long answerId;

    /**
     * 回答得分，建议 0 到 100
     */
    private Integer score;

    /**
     * 回答等级：GOOD=良好，NORMAL=一般，WEAK=薄弱
     */
    private String level;

    /**
     * 已命中的要点 JSON
     */
    @TableField("matched_points_json")
    private String matchedPointsJson;

    /**
     * 缺失要点 JSON
     */
    @TableField("missing_points_json")
    private String missingPointsJson;

    /**
     * 暴露的问题或风险 JSON
     */
    @TableField("risk_points_json")
    private String riskPointsJson;

    /**
     * 下一步动作：FOLLOW_UP=追问，SWITCH_TOPIC=换题，NEXT_STAGE=进入下一阶段，
     * FINISH=结束面试
     */
    private String nextAction;

    /**
     * 本次决策使用的候选问题池快照 JSON，用于审计与复盘
     */
    @TableField("candidate_pool_snapshot_json")
    private String candidatePoolSnapshotJson;

    /**
     * 被选中的下一题ID，可为空（如结束面试时无下一题）
     */
    private Long selectedNextQuestionId;

    /**
     * 简短评估说明
     */
    private String evaluationText;

    /**
     * 评估使用的模型名称
     */
    private String modelName;

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
