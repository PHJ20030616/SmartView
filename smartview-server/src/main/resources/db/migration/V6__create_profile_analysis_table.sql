-- =====================================================
-- V6__create_profile_analysis_table.sql
-- 创建画像分析结果表：用户选择面试方向后，由 FastAPI 基于已确认简历、
-- 简历向量片段和知识/面经检索结果生成，作为阶段计划与出题策略的内部准备材料。
--
-- 关键设计：
-- 1. 只在分析成功时写入一行，失败/重试由 ai_task 承载；
-- 2. 唯一索引 (resume_profile_id, role_direction, profile_version) 保证
--    同一简历版本、同一面试方向只有一份有效画像分析；
-- 3. 软删除标记与全局逻辑删除配置保持一致。
-- =====================================================

CREATE TABLE `profile_analysis` (
    -- 分析结果唯一标识，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分析结果ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 对应的简历画像 ID，外键关联 resume_profile 表
    `resume_profile_id` BIGINT NOT NULL COMMENT '简历画像ID',

    -- 面试方向：JAVA_BACKEND=Java后端，AGENT_DEVELOPMENT=Agent开发
    `role_direction` VARCHAR(50) NOT NULL COMMENT '面试方向',

    -- 技能标签 JSON，例如 [{"skill":"Java","level":"EXPERT","source":"PROJECT"}]
    `skill_tags_json` JSON NULL COMMENT '技能标签JSON',

    -- 项目关系图谱 JSON，包括项目、技术栈、职责、亮点
    `project_graph_json` JSON NULL COMMENT '项目关系图谱JSON',

    -- 能力线索 JSON，例如工程能力、Agent能力、系统设计能力
    `capability_hints_json` JSON NULL COMMENT '能力线索JSON',

    -- 风险点 JSON，例如项目描述空泛、技术深度不足
    `risk_points_json` JSON NULL COMMENT '风险点JSON',

    -- 建议面试主题 JSON
    `suggested_topics_json` JSON NULL COMMENT '建议面试主题JSON',

    -- 阶段覆盖目标 JSON，例如八股、项目追问、场景题的重点
    `stage_targets_json` JSON NULL COMMENT '阶段覆盖目标JSON',

    -- 对应的简历画像版本号，保证使用正确版本的简历数据
    `profile_version` INT NOT NULL COMMENT '简历画像版本号',

    -- 生成该分析结果使用的模型名称
    `model_name` VARCHAR(100) NULL COMMENT '生成模型名称',

    -- 模型版本或配置版本
    `model_version` VARCHAR(50) NULL COMMENT '模型版本',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`),

    -- 唯一约束：同一简历版本、同一面试方向只允许一份有效画像分析
    -- 设计取舍：分析只在成功时写入，失败重试不重建同键行，因此唯一索引
    -- 不需要包含 deleted；软删除仅用于整条画像删除等运维场景。
    UNIQUE KEY `uk_profile_analysis_unique` (`resume_profile_id`, `role_direction`, `profile_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='画像分析结果表';

-- 用户ID索引，用于查询某用户的所有画像分析
CREATE INDEX `idx_user_id` ON `profile_analysis`(`user_id`);

-- 简历画像ID索引，用于查询某画像的全部方向分析
CREATE INDEX `idx_resume_profile_id` ON `profile_analysis`(`resume_profile_id`);

-- 软删除标记索引
CREATE INDEX `idx_deleted` ON `profile_analysis`(`deleted`);

-- 用户ID外键约束
ALTER TABLE `profile_analysis` ADD CONSTRAINT `fk_profile_analysis_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;

-- 简历画像ID外键约束
ALTER TABLE `profile_analysis` ADD CONSTRAINT `fk_profile_analysis_resume_profile_id`
    FOREIGN KEY (`resume_profile_id`) REFERENCES `resume_profile`(`id`) ON DELETE CASCADE;
