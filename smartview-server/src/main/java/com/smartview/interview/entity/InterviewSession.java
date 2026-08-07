package com.smartview.interview.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试会话实体类
 *
 * 功能说明：
 * - 映射数据库 interview_session 表
 * - 保存一次模拟面试的主状态，是面试状态恢复的核心表（plan_1.0.md 7.2）
 * - MySQL 保存权威状态，页面刷新后根据会话 ID + currentQuestionId 恢复当前题目
 *
 * 关键设计：
 * 1. 状态/阶段/结束原因以字符串存储（沿用全局约定），取值范围为示例值，
 *    最终由 Task 5.4 的 StagePolicyEngine 与后续枚举类统一定义；
 * 2. version 为乐观锁版本号，回答提交时必须校验，避免并发请求覆盖会话状态；
 * 3. stagePlanJson 是下一题选择的约束来源，stageCoverageJson 是判断
 *    追问/换题/切阶段/结束的依据；
 * 4. graphThreadId 把业务会话与 LangGraph 图状态关联，latestCheckpointId
 *    为后续跨天恢复预留；
 * 5. 软删除：deleted 字段配合 @TableLogic 实现逻辑删除。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("interview_session")
public class InterviewSession {

    /**
     * 面试会话ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID，外键关联 user 表，用于权限控制和用户维度查询
     */
    private Long userId;

    /**
     * 使用的简历画像ID，外键关联 resume_profile 表
     */
    private Long resumeProfileId;

    /**
     * 使用的简历分析ID，外键关联 profile_analysis 表
     * 会话创建前必须已生成对应方向的画像分析
     */
    private Long profileAnalysisId;

    /**
     * 用户选择的面试方向：JAVA_BACKEND=Java后端，AGENT_DEVELOPMENT=Agent开发
     * 数据库存储字符串，业务代码应使用 RoleDirection 枚举
     */
    private String roleDirection;

    /**
     * 会话生命周期状态：CREATED=已创建未开始，IN_PROGRESS=面试中，
     * REPORTING=报告生成中，COMPLETED=已完成，CANCELLED=用户放弃不生成报告，
     * FAILED=异常失败
     */
    private String status;

    /**
     * 当前内部阶段：BASIC=基础八股，PROJECT=项目追问，SCENARIO=场景题，
     * REPORT=报告阶段，仅系统内部使用，不直接暴露给用户
     */
    private String currentStage;

    /**
     * 当前主题，例如某个简历项目、某个技术点、某类业务场景
     */
    private String currentTopic;

    /**
     * 当前正在等待用户回答的问题ID，页面刷新后据此恢复当前题目
     * 可为空：会话刚创建尚无题目
     */
    private Long currentQuestionId;

    /**
     * 已提出的问题数量
     */
    private Integer questionCount;

    /**
     * 预期最少问题数，用于给用户展示大致进度
     */
    private Integer expectedMinQuestions;

    /**
     * 预期最多问题数，用于限制面试不要无限追问
     */
    private Integer expectedMaxQuestions;

    /**
     * 阶段计划 JSON：阶段顺序、题量边界、必覆盖主题、切换条件，
     * 是下一题选择的约束来源
     */
    @TableField("stage_plan_json")
    private String stagePlanJson;

    /**
     * 阶段覆盖情况 JSON：已问主题、追问深度、切换原因，
     * 是判断追问/换题/切阶段/结束的依据
     */
    @TableField("stage_coverage_json")
    private String stageCoverageJson;

    /**
     * LangGraph 线程ID，把业务会话和图状态关联起来，后续可恢复流程
     */
    private String graphThreadId;

    /**
     * 最近一次 LangGraph checkpoint ID，为后续跨天恢复预留
     */
    private String latestCheckpointId;

    /**
     * 乐观锁版本号，回答提交时必须校验，避免并发请求覆盖会话状态。
     * 注意：项目未启用 MyBatis-Plus 的 @Version + OptimisticLockerInnerInterceptor
     * （ResumeProfile.version 同理），因此乐观锁的校验与自增由业务代码手动实现。
     */
    private Integer version;

    /**
     * 结束原因（示例值，最终由 StagePolicyEngine 定义）：
     * PLAN_COMPLETED / QUESTION_LIMIT / USER_FINISHED_EARLY / USER_CANCELLED /
     * QUALITY_TOO_LOW / NO_VALID_QUESTION / FAILED
     */
    private String endReason;

    /**
     * 面试开始时间
     */
    private LocalDateTime startedAt;

    /**
     * 面试结束时间
     */
    private LocalDateTime endedAt;

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
