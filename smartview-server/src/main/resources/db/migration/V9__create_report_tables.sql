-- =====================================================
-- V9__create_report_tables.sql
-- 创建报告相关两表：面试报告（interview_report）、参考答案（reference_answer）。
--
-- 设计说明（与 plan_1.0.md 7.1.9/7.1.10 及 contracts/web-api/openapi.yaml 保持一致）：
-- 1. interview_report 保存一次面试结束后的整体复盘。会话终态与报告生成状态相互独立
--    （plan 5.4：报告失败不得把已经结束的面试改成失败会话），因此报告自身维护
--    status 状态机：GENERATING=生成中，SUCCESS=成功，FAILED=失败；
-- 2. 唯一索引 uk_interview_report_session_deleted 保证一个会话最多一份有效报告
--    （deleted=0）：报告生成失败/重试走同一条记录原地更新
--    （GENERATING→SUCCESS/FAILED），重复投递的报告生成任务会被唯一约束拒绝，
--    天然防重复生成；同时兼容"软删除→重建"（与 interview_answer 的
--    (question_id, deleted) 模式一致）；
-- 3. reference_answer 按 plan 不含 user_id 冗余字段：通过 report_id →
--    interview_report.user_id 传递关联到用户，避免与画像/契约漂移；
-- 4. reference_answer 唯一索引 (report_id, question_id, deleted) 保证同一报告内
--    每道题至多一份有效参考答案，防止重复生成时同题多答；
-- 5. 状态/枚举字段以字符串存储（沿用全局约定），取值与 web-api 契约一致：
--    readiness_level ∈ NOT_READY/NEEDS_PRACTICE/READY/WELL_PREPARED，
--    answer_type ∈ BASIC_KEY_POINTS/PROJECT_STRUCTURE/SCENARIO_FRAMEWORK；
--    overall_score / role_fit_score 为 0-100 整数；
-- 6. 索引命名统一使用带表名前缀的长名（沿用 V7 约定），避免 H2 等数据库
--    索引名 schema 级全局唯一导致的跨表同名冲突；
-- 7. 两表不单独创建 deleted 索引：deleted 仅 0/1 两值、选择性极低（沿用 V7 取舍）；
-- 8. 外键均为归属引用，采用 ON DELETE CASCADE（沿用 V7 约定）；报告两表与
--    既有表之间不存在循环引用，无需建表后追加外键。
-- =====================================================

-- =====================================================
-- 面试报告表：保存一次面试结束后的整体复盘
-- =====================================================
CREATE TABLE `interview_report` (
    -- 报告ID，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '报告ID',

    -- 对应面试会话ID，外键关联 interview_session 表；一个会话最多一份有效报告
    `session_id` BIGINT NOT NULL COMMENT '面试会话ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 使用的简历画像ID，外键关联 resume_profile 表
    `resume_profile_id` BIGINT NOT NULL COMMENT '简历画像ID',

    -- 综合得分，建议 0 到 100
    `overall_score` INT NULL COMMENT '综合得分',

    -- 面试准备度等级：NOT_READY=准备不足，NEEDS_PRACTICE=需加强练习，
    -- READY=已准备就绪，WELL_PREPARED=准备充分（与 web-api 契约枚举一致）
    `readiness_level` VARCHAR(20) NULL COMMENT '面试准备度等级',

    -- 岗位匹配度得分，建议 0 到 100
    `role_fit_score` INT NULL COMMENT '岗位匹配度得分',

    -- 总体评价，面向用户的文字总结
    `summary` TEXT NULL COMMENT '总体评价',

    -- 优势点 JSON 数组，如 ["项目架构清晰","基础知识扎实"]
    `strengths_json` JSON NULL COMMENT '优势点JSON',

    -- 薄弱点 JSON 数组
    `weaknesses_json` JSON NULL COMMENT '薄弱点JSON',

    -- 风险点 JSON 数组
    `risk_points_json` JSON NULL COMMENT '风险点JSON',

    -- 学习建议 JSON，如 [{topic, reason, resources}]
    `suggestions_json` JSON NULL COMMENT '学习建议JSON',

    -- 覆盖情况 JSON，如 {basicCoverage, projectCoverage, scenarioCoverage} 覆盖比例
    `coverage_json` JSON NULL COMMENT '覆盖情况JSON',

    -- 报告状态：GENERATING=生成中，SUCCESS=生成成功，FAILED=生成失败
    -- （与 web-api 契约枚举一致，默认 GENERATING）
    `status` VARCHAR(20) NOT NULL DEFAULT 'GENERATING' COMMENT '报告状态',

    -- 报告生成完成时间，可为空（生成中或失败时为空）
    `generated_at` DATETIME NULL COMMENT '报告生成时间',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='面试报告表';

-- 唯一约束：同一会话最多一份有效报告（deleted=0）+ 至多一条软删除历史（deleted=1）。
-- 设计取舍：与 interview_answer 的 (question_id, deleted) 模式一致——
-- 允许"有效报告→软删除→重建有效报告"，但同一会话不允许存在两条软删除报告。
-- 同时该索引的最左前缀 session_id 可复用为会话维度查询的索引。
CREATE UNIQUE INDEX `uk_interview_report_session_deleted`
    ON `interview_report`(`session_id`, `deleted`);

-- 用户ID索引，用于查询某用户的全部报告
CREATE INDEX `idx_interview_report_user_id` ON `interview_report`(`user_id`);

-- 简历画像ID索引，用于按画像反查报告
CREATE INDEX `idx_interview_report_resume_profile_id` ON `interview_report`(`resume_profile_id`);

-- 会话ID外键约束（复用 uk_interview_report_session_deleted 的最左前缀索引）
ALTER TABLE `interview_report` ADD CONSTRAINT `fk_interview_report_session_id`
    FOREIGN KEY (`session_id`) REFERENCES `interview_session`(`id`) ON DELETE CASCADE;

-- 用户ID外键约束
ALTER TABLE `interview_report` ADD CONSTRAINT `fk_interview_report_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;

-- 简历画像ID外键约束
ALTER TABLE `interview_report` ADD CONSTRAINT `fk_interview_report_resume_profile_id`
    FOREIGN KEY (`resume_profile_id`) REFERENCES `resume_profile`(`id`) ON DELETE CASCADE;


-- =====================================================
-- 参考答案表：保存每道题的复盘参考内容
-- =====================================================
CREATE TABLE `reference_answer` (
    -- 参考答案ID，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '参考答案ID',

    -- 所属报告ID，外键关联 interview_report 表
    `report_id` BIGINT NOT NULL COMMENT '报告ID',

    -- 所属面试会话ID，外键关联 interview_session 表
    `session_id` BIGINT NOT NULL COMMENT '面试会话ID',

    -- 对应问题ID，外键关联 interview_question 表
    `question_id` BIGINT NOT NULL COMMENT '问题ID',

    -- 答案类型：BASIC_KEY_POINTS=基础题关键要点，PROJECT_STRUCTURE=项目题回答结构，
    -- SCENARIO_FRAMEWORK=场景题答题框架（与 web-api 契约枚举一致）
    `answer_type` VARCHAR(30) NOT NULL COMMENT '答案类型',

    -- 参考答案正文
    `reference_content` MEDIUMTEXT NULL COMMENT '参考答案正文',

    -- 关键要点 JSON 数组，如 ["要点一","要点二"]
    `key_points_json` JSON NULL COMMENT '关键要点JSON',

    -- 场景题权衡点 JSON 数组，如 [{aspect, options}]
    `tradeoffs_json` JSON NULL COMMENT '权衡点JSON',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='参考答案表';

-- 唯一约束：同一报告内每道题最多一份有效参考答案（deleted=0）+ 至多一条软删除历史。
-- 设计取舍：报告重复生成时同题不能重复落多份参考答案，由数据库唯一约束兜底；
-- 最左前缀 report_id 同时复用为报告维度查询的索引。
CREATE UNIQUE INDEX `uk_reference_answer_report_question_deleted`
    ON `reference_answer`(`report_id`, `question_id`, `deleted`);

-- 会话ID索引，用于查询某会话的全部参考答案
CREATE INDEX `idx_reference_answer_session_id` ON `reference_answer`(`session_id`);

-- 问题ID索引，用于按问题反查参考答案
CREATE INDEX `idx_reference_answer_question_id` ON `reference_answer`(`question_id`);

-- 报告ID外键约束（复用 uk_reference_answer_report_question_deleted 的最左前缀索引）
ALTER TABLE `reference_answer` ADD CONSTRAINT `fk_reference_answer_report_id`
    FOREIGN KEY (`report_id`) REFERENCES `interview_report`(`id`) ON DELETE CASCADE;

-- 会话ID外键约束
ALTER TABLE `reference_answer` ADD CONSTRAINT `fk_reference_answer_session_id`
    FOREIGN KEY (`session_id`) REFERENCES `interview_session`(`id`) ON DELETE CASCADE;

-- 问题ID外键约束
ALTER TABLE `reference_answer` ADD CONSTRAINT `fk_reference_answer_question_id`
    FOREIGN KEY (`question_id`) REFERENCES `interview_question`(`id`) ON DELETE CASCADE;
