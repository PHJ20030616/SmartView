package com.smartview.report.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试报告实体类
 *
 * 功能说明：
 * - 映射数据库 interview_report 表
 * - 保存一次面试结束后的整体复盘（plan_1.0.md 7.1.9）
 *
 * 关键设计：
 * 1. 会话终态与报告生成状态相互独立（plan 5.4）：报告失败不得把已结束的面试
 *    改成失败会话，因此报告自身维护 status 状态机（GENERATING/SUCCESS/FAILED）；
 * 2. 一个会话最多一份有效报告：唯一索引 (session_id, deleted) 由数据库兜底防重复，
 *    报告生成失败/重试走同一条记录原地更新，重复投递的任务会被唯一约束拒绝；
 * 3. 各维度结果以 JSON 存储：strengthsJson/weaknessesJson/riskPointsJson 为字符串数组，
 *    suggestionsJson 为建议对象数组，coverageJson 为各阶段覆盖比例对象，
 *    序列化/反序列化由业务层（Task 6.x）负责，实体保持透传；
 * 4. overallScore / roleFitScore 为 0-100 整数，readinessLevel 取值与
 *    contracts/web-api/openapi.yaml 枚举一致（NOT_READY/NEEDS_PRACTICE/READY/WELL_PREPARED）；
 * 5. 软删除：deleted 字段配合 @TableLogic 实现逻辑删除。
 *
 * @author SmartView Team
 * @since 2026-08-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_report")
public class InterviewReport {

    /**
     * 报告ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对应面试会话ID，外键关联 interview_session 表，一个会话最多一份有效报告
     */
    private Long sessionId;

    /**
     * 所属用户ID，外键关联 user 表，用于权限控制和用户维度查询
     */
    private Long userId;

    /**
     * 使用的简历画像ID，外键关联 resume_profile 表
     */
    private Long resumeProfileId;

    /**
     * 综合得分，建议 0 到 100
     */
    private Integer overallScore;

    /**
     * 面试准备度等级：NOT_READY=准备不足，NEEDS_PRACTICE=需加强练习，
     * READY=已准备就绪，WELL_PREPARED=准备充分（与 web-api 契约枚举一致）
     */
    private String readinessLevel;

    /**
     * 岗位匹配度得分，建议 0 到 100
     */
    private Integer roleFitScore;

    /**
     * 总体评价，面向用户的文字总结
     */
    private String summary;

    /**
     * 优势点 JSON，字符串数组，如 ["项目架构清晰","基础知识扎实"]
     */
    @TableField("strengths_json")
    private String strengthsJson;

    /**
     * 薄弱点 JSON，字符串数组
     */
    @TableField("weaknesses_json")
    private String weaknessesJson;

    /**
     * 风险点 JSON，字符串数组
     */
    @TableField("risk_points_json")
    private String riskPointsJson;

    /**
     * 学习建议 JSON，建议对象数组，如 [{topic, reason, resources}]
     */
    @TableField("suggestions_json")
    private String suggestionsJson;

    /**
     * 覆盖情况 JSON，如 {basicCoverage, projectCoverage, scenarioCoverage} 覆盖比例
     */
    @TableField("coverage_json")
    private String coverageJson;

    /**
     * 报告状态：GENERATING=生成中，SUCCESS=生成成功，FAILED=生成失败
     * （与 web-api 契约枚举一致）
     */
    private String status;

    /**
     * 报告生成完成时间，可为空（生成中或失败时为空）
     */
    private LocalDateTime generatedAt;

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
