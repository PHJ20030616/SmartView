package com.smartview.profile.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 画像分析结果实体类
 *
 * 功能说明：
 * - 映射数据库 profile_analysis 表
 * - 存储用户选择面试方向后由 FastAPI 生成的该方向画像分析结果
 * - 作为阶段计划与出题策略的内部准备材料，不直接面向用户展示
 *
 * 关键设计：
 * 1. 只在分析成功时写入一行，失败/重试由 ai_task 承载；
 * 2. 唯一索引 (resume_profile_id, role_direction, profile_version) 保证
 *    同一简历版本、同一面试方向只有一份有效画像分析；
 * 3. 各 JSON 字段与 MQ 结果契约（contracts/mq/profile_analyze_result.schema.json）
 *    及 ai-api 契约的 AnalyzeProfileResponse 保持字段一致。
 *
 * 业务流程：
 * 1. 用户选择面试方向 → Spring 校验简历向量已入库 → 创建 PROFILE_ANALYZE 任务
 * 2. FastAPI 消费任务，结合已确认简历、简历向量片段、知识/面经检索生成分析
 * 3. Spring 消费结果消息 → 写入本表（成功时 INSERT 或按唯一键 UPDATE）
 *
 * @author SmartView Team
 * @since 2026-08-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("profile_analysis")
public class ProfileAnalysis {

    /**
     * 分析结果ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID，外键关联 user 表，用于权限控制和用户维度查询
     */
    private Long userId;

    /**
     * 对应的简历画像 ID，外键关联 resume_profile 表
     */
    private Long resumeProfileId;

    /**
     * 面试方向：JAVA_BACKEND=Java后端，AGENT_DEVELOPMENT=Agent开发
     * 数据库存储字符串，业务代码应使用统一的字符串常量或校验枚举
     */
    private String roleDirection;

    /**
     * 技能标签 JSON，例如 [{"skill":"Java","level":"EXPERT","source":"PROJECT"}]
     */
    @TableField("skill_tags_json")
    private String skillTagsJson;

    /**
     * 项目关系图谱 JSON，包括项目、技术栈、职责、亮点
     */
    @TableField("project_graph_json")
    private String projectGraphJson;

    /**
     * 能力线索 JSON，例如工程能力、Agent能力、系统设计能力
     */
    @TableField("capability_hints_json")
    private String capabilityHintsJson;

    /**
     * 风险点 JSON，例如项目描述空泛、技术深度不足
     */
    @TableField("risk_points_json")
    private String riskPointsJson;

    /**
     * 建议面试主题 JSON
     */
    @TableField("suggested_topics_json")
    private String suggestedTopicsJson;

    /**
     * 阶段覆盖目标 JSON，例如八股、项目追问、场景题的重点
     */
    @TableField("stage_targets_json")
    private String stageTargetsJson;

    /**
     * 对应的简历画像版本号，保证使用正确版本的简历数据
     * 唯一索引 (resume_profile_id, role_direction, profile_version) 的组成字段
     */
    @TableField("profile_version")
    private Integer profileVersion;

    /**
     * 生成该分析结果使用的模型名称
     */
    private String modelName;

    /**
     * 模型版本或配置版本
     */
    private String modelVersion;

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
