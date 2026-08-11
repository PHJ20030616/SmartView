package com.smartview.report.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 参考答案实体类
 *
 * 功能说明：
 * - 映射数据库 reference_answer 表
 * - 保存每道题的复盘参考内容（plan_1.0.md 7.1.10）
 *
 * 关键设计：
 * 1. 通过 reportId 关联报告，reportId + questionId 唯一定位"某报告内某道题的参考答案"，
 *    唯一索引 (report_id, question_id, deleted) 由数据库兜底防重复生成时同题多答；
 * 2. 按 plan 不含 user_id 冗余字段：关联用户通过 report_id → interview_report.user_id
 *    传递，避免与画像/契约字段漂移；
 * 3. answerType 取值与 contracts/web-api/openapi.yaml 枚举一致：
 *    BASIC_KEY_POINTS=基础题关键要点，PROJECT_STRUCTURE=项目题回答结构，
 *    SCENARIO_FRAMEWORK=场景题答题框架；
 * 4. keyPointsJson 为关键要点字符串数组，tradeoffsJson 为场景题权衡点对象数组
 *    （如 [{aspect, options}]），序列化/反序列化由业务层（Task 6.x）负责；
 * 5. 软删除：deleted 字段配合 @TableLogic 实现逻辑删除。
 *
 * @author SmartView Team
 * @since 2026-08-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("reference_answer")
public class ReferenceAnswer {

    /**
     * 参考答案ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属报告ID，外键关联 interview_report 表
     */
    private Long reportId;

    /**
     * 所属面试会话ID，外键关联 interview_session 表，冗余存储便于按会话查询全部参考答案
     */
    private Long sessionId;

    /**
     * 对应问题ID，外键关联 interview_question 表
     */
    private Long questionId;

    /**
     * 答案类型：BASIC_KEY_POINTS=基础题关键要点，PROJECT_STRUCTURE=项目题回答结构，
     * SCENARIO_FRAMEWORK=场景题答题框架（与 web-api 契约枚举一致）
     */
    private String answerType;

    /**
     * 参考答案正文
     */
    private String referenceContent;

    /**
     * 关键要点 JSON，字符串数组，如 ["要点一","要点二"]
     */
    @TableField("key_points_json")
    private String keyPointsJson;

    /**
     * 场景题权衡点 JSON，权衡点对象数组，如 [{aspect, options}]
     */
    @TableField("tradeoffs_json")
    private String tradeoffsJson;

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
