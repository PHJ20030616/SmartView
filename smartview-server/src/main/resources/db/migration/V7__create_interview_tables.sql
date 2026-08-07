-- =====================================================
-- V7__create_interview_tables.sql
-- 创建面试相关四表：会话、问题、回答、回答评估。
--
-- 设计说明（与 plan_1.0.md 7.1/7.2 保持一致）：
-- 1. interview_session 是面试状态恢复的核心表，MySQL 保存权威状态，
--    页面刷新后根据 session 主键 + current_question_id 恢复当前题目；
-- 2. 四表均通过外键关联（归属字段 CASCADE、可空引用 SET NULL），
--    与 resume 系表约定一致；interview_session.current_question_id 与
--    interview_question.session_id 存在循环引用，因此该外键在建表后
--    通过 ALTER 追加（必须先创建被引用的 interview_question）；
-- 3. interview_answer 唯一索引 (question_id, deleted) 保证同一问题
--    最多一份有效回答（deleted=0），同时兼容软删除（至多一条历史）；
-- 4. 状态/枚举字段以字符串存储（沿用全局约定），取值范围为示例值，
--    最终由 Task 5.4 的 StagePolicyEngine 与后续枚举类统一定义；
-- 5. 索引命名统一使用带表名前缀的长名（idx_{表}_{字段} / uk_{表}_...），
--    与早期迁移（V1/V2/V6 使用 idx_user_id 等短名）不同，避免 H2 等
--    数据库索引名 schema 级全局唯一导致的跨表同名冲突（V3 即为该问题重命名）；
-- 6. 四表不单独创建 deleted 软删除索引：deleted 仅 0/1 两值、选择性极低，
--    逻辑删除过滤对索引收益有限，与早期表带 idx_deleted 的差异为有意取舍；
-- 7. answer_evaluation.selected_next_question_id 不建外键：作为决策快照字段，
--    被选中的下一题可能被软删除，仅作审计引用（与外键约束无关）。
-- =====================================================

-- =====================================================
-- 面试会话表：保存一次模拟面试的主状态
-- =====================================================
CREATE TABLE `interview_session` (
    -- 面试会话ID，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '面试会话ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 使用的简历画像ID，外键关联 resume_profile 表
    `resume_profile_id` BIGINT NOT NULL COMMENT '简历画像ID',

    -- 使用的简历分析ID，外键关联 profile_analysis 表
    `profile_analysis_id` BIGINT NOT NULL COMMENT '简历分析ID',

    -- 用户选择的面试方向：JAVA_BACKEND=Java后端，AGENT_DEVELOPMENT=Agent开发
    `role_direction` VARCHAR(50) NOT NULL COMMENT '面试方向',

    -- 会话生命周期状态：CREATED=已创建未开始，IN_PROGRESS=面试中，
    -- REPORTING=报告生成中，COMPLETED=已完成，CANCELLED=用户放弃不生成报告，
    -- FAILED=异常失败
    `status` VARCHAR(20) NOT NULL DEFAULT 'CREATED' COMMENT '会话状态',

    -- 当前内部阶段：BASIC=基础八股，PROJECT=项目追问，SCENARIO=场景题，
    -- REPORT=报告阶段，仅系统内部使用，不直接暴露给用户
    `current_stage` VARCHAR(20) NULL COMMENT '当前内部阶段',

    -- 当前主题，例如某个简历项目、某个技术点、某类业务场景
    `current_topic` VARCHAR(255) NULL COMMENT '当前主题',

    -- 当前正在等待用户回答的问题ID，页面刷新后据此恢复当前题目，
    -- 可为空（会话刚创建尚无题目）
    `current_question_id` BIGINT NULL COMMENT '当前问题ID',

    -- 已提出的问题数量
    `question_count` INT NOT NULL DEFAULT 0 COMMENT '已提出问题数',

    -- 预期最少问题数，用于进度展示
    `expected_min_questions` INT NULL COMMENT '预期最少问题数',

    -- 预期最多问题数，用于限制面试不要无限追问
    `expected_max_questions` INT NULL COMMENT '预期最多问题数',

    -- 阶段计划 JSON：阶段顺序、题量边界、必覆盖主题、切换条件，是下一题选择的约束来源
    `stage_plan_json` JSON NULL COMMENT '阶段计划JSON',

    -- 阶段覆盖情况 JSON：已问主题、追问深度、切换原因，是判断追问/换题/切阶段/结束的依据
    `stage_coverage_json` JSON NULL COMMENT '阶段覆盖JSON',

    -- LangGraph 线程ID，用于把业务会话和图状态关联起来，后续可恢复流程
    `graph_thread_id` VARCHAR(64) NULL COMMENT 'LangGraph线程ID',

    -- 最近一次 LangGraph checkpoint ID，为后续跨天恢复预留
    `latest_checkpoint_id` VARCHAR(128) NULL COMMENT 'LangGraph checkpointID',

    -- 乐观锁版本号，回答提交时必须校验，避免并发请求覆盖会话状态
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',

    -- 结束原因（示例值，最终由 StagePolicyEngine 定义）：
    -- PLAN_COMPLETED=阶段计划完成正常结束，QUESTION_LIMIT=达到总题量上限，
    -- USER_FINISHED_EARLY=用户提前结束并生成阶段性报告，
    -- USER_CANCELLED=用户放弃不生成报告，QUALITY_TOO_LOW=连续多题质量过低止损，
    -- NO_VALID_QUESTION=候选池耗尽无有效下一题，FAILED=异常失败
    `end_reason` VARCHAR(50) NULL COMMENT '结束原因',

    -- 面试开始时间
    `started_at` DATETIME NULL COMMENT '开始时间',

    -- 面试结束时间
    `ended_at` DATETIME NULL COMMENT '结束时间',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='面试会话表';

-- 用户ID索引，用于查询某用户的全部面试会话
CREATE INDEX `idx_interview_session_user_id` ON `interview_session`(`user_id`);

-- 会话状态索引，用于按状态筛选会话（如待处理、面试中）
CREATE INDEX `idx_interview_session_status` ON `interview_session`(`status`);

-- 简历画像ID索引，用于查询某画像关联的会话
CREATE INDEX `idx_interview_session_resume_profile_id` ON `interview_session`(`resume_profile_id`);

-- 简历分析ID索引，用于按画像分析反查会话
CREATE INDEX `idx_interview_session_profile_analysis_id` ON `interview_session`(`profile_analysis_id`);

-- 当前问题ID索引，外键 current_question_id 复用此索引
CREATE INDEX `idx_interview_session_current_question_id` ON `interview_session`(`current_question_id`);

-- 用户ID外键约束
ALTER TABLE `interview_session` ADD CONSTRAINT `fk_interview_session_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;

-- 简历画像ID外键约束
ALTER TABLE `interview_session` ADD CONSTRAINT `fk_interview_session_resume_profile_id`
    FOREIGN KEY (`resume_profile_id`) REFERENCES `resume_profile`(`id`) ON DELETE CASCADE;

-- 简历分析ID外键约束
ALTER TABLE `interview_session` ADD CONSTRAINT `fk_interview_session_profile_analysis_id`
    FOREIGN KEY (`profile_analysis_id`) REFERENCES `profile_analysis`(`id`) ON DELETE CASCADE;


-- =====================================================
-- 面试问题表：保存系统提出的每一道题
-- =====================================================
CREATE TABLE `interview_question` (
    -- 问题ID，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '问题ID',

    -- 所属面试会话ID，外键关联 interview_session 表
    `session_id` BIGINT NOT NULL COMMENT '面试会话ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 当前会话中的问题序号，从 1 递增，同一会话内唯一
    `question_order` INT NOT NULL COMMENT '问题序号',

    -- 父问题ID，用于表示追问关系（如 OPENING 题的 FOLLOW_UP 追问），
    -- 可为空：第一题为空，其余追问指向其父问题
    `parent_question_id` BIGINT NULL COMMENT '父问题ID',

    -- 所属阶段：BASIC=基础八股，PROJECT=项目追问，SCENARIO=场景题
    `stage` VARCHAR(20) NULL COMMENT '所属阶段',

    -- 问题类型：OPENING=开场题，FOLLOW_UP=追问，SWITCH_TOPIC=换题，STAGE_ENTRY=阶段入口题
    `question_type` VARCHAR(30) NULL COMMENT '问题类型',

    -- 问题主题，例如某个技术点、某个项目、某类场景
    `topic` VARCHAR(255) NULL COMMENT '问题主题',

    -- 问题正文
    `question_text` TEXT NOT NULL COMMENT '问题正文',

    -- 来源类型：KNOWLEDGE_BASE=八股知识库，EXPERIENCE_CASE=面经案例，
    -- RESUME_PROJECT=简历项目，MIXED=混合来源
    `source_type` VARCHAR(30) NULL COMMENT '来源类型',

    -- 引用的八股知识片段信息 JSON，用于溯源与复盘
    `knowledge_refs_json` JSON NULL COMMENT '八股知识引用JSON',

    -- 引用的面经案例信息 JSON
    `case_refs_json` JSON NULL COMMENT '面经案例引用JSON',

    -- 期望回答要点 JSON，作为回答评估的对照依据
    `expected_points_json` JSON NULL COMMENT '期望回答要点JSON',

    -- 问题状态：ASKED=已提问，ANSWERED=已回答，SKIPPED=已跳过
    `status` VARCHAR(20) NOT NULL DEFAULT 'ASKED' COMMENT '问题状态',

    -- 提问时间
    `asked_at` DATETIME NULL COMMENT '提问时间',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='面试问题表';

-- 同一会话内问题序号唯一，同时作为"按会话查询问题"的索引
CREATE UNIQUE INDEX `uk_interview_question_session_order` ON `interview_question`(`session_id`, `question_order`);

-- 用户ID索引，用于查询某用户的全部问题
CREATE INDEX `idx_interview_question_user_id` ON `interview_question`(`user_id`);

-- 父问题ID索引，外键 parent_question_id 复用此索引
CREATE INDEX `idx_interview_question_parent_id` ON `interview_question`(`parent_question_id`);

-- 会话ID外键约束
ALTER TABLE `interview_question` ADD CONSTRAINT `fk_interview_question_session_id`
    FOREIGN KEY (`session_id`) REFERENCES `interview_session`(`id`) ON DELETE CASCADE;

-- 用户ID外键约束
ALTER TABLE `interview_question` ADD CONSTRAINT `fk_interview_question_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;

-- 父问题ID外键约束：父问题被删除时追问置空
ALTER TABLE `interview_question` ADD CONSTRAINT `fk_interview_question_parent_id`
    FOREIGN KEY (`parent_question_id`) REFERENCES `interview_question`(`id`) ON DELETE SET NULL;


-- =====================================================
-- 用户回答表：保存用户对每个问题的回答
-- =====================================================
CREATE TABLE `interview_answer` (
    -- 回答ID，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '回答ID',

    -- 所属面试会话ID，外键关联 interview_session 表
    `session_id` BIGINT NOT NULL COMMENT '面试会话ID',

    -- 对应问题ID，外键关联 interview_question 表
    `question_id` BIGINT NOT NULL COMMENT '问题ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 用户回答文本
    `answer_text` MEDIUMTEXT NULL COMMENT '回答文本',

    -- 回答方式：TEXT=文本，后续可扩展 VOICE_TO_TEXT=语音转文本
    `answer_mode` VARCHAR(20) NOT NULL DEFAULT 'TEXT' COMMENT '回答方式',

    -- 用户作答耗时，单位秒
    `duration_seconds` INT NULL COMMENT '作答耗时(秒)',

    -- 回答提交幂等ID，可来自 Idempotency-Key 请求头，防止重复提交生成多道下一题
    `request_id` VARCHAR(64) NULL COMMENT '幂等请求ID',

    -- 提交时间
    `submitted_at` DATETIME NOT NULL COMMENT '提交时间',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户回答表';

-- 唯一约束：同一问题最多一份有效回答（deleted=0）+ 至多一条软删除历史（deleted=1）。
-- 设计取舍：不直接对 question_id 建唯一索引，避免软删除后无法重建新回答；
-- (question_id, deleted) 组合允许"有效回答→软删除→重建有效回答"，
-- 但同一问题不允许存在两条软删除记录（业务上同一问题只会有一条真实回答线）。
CREATE UNIQUE INDEX `uk_interview_answer_question_deleted`
    ON `interview_answer`(`question_id`, `deleted`);

-- 会话ID索引，用于查询某会话的全部回答
CREATE INDEX `idx_interview_answer_session_id` ON `interview_answer`(`session_id`);

-- 用户ID索引，用于查询某用户的全部回答
CREATE INDEX `idx_interview_answer_user_id` ON `interview_answer`(`user_id`);

-- 会话ID外键约束
ALTER TABLE `interview_answer` ADD CONSTRAINT `fk_interview_answer_session_id`
    FOREIGN KEY (`session_id`) REFERENCES `interview_session`(`id`) ON DELETE CASCADE;

-- 问题ID外键约束（复用 uk_interview_answer_question_deleted 的最左前缀索引）
ALTER TABLE `interview_answer` ADD CONSTRAINT `fk_interview_answer_question_id`
    FOREIGN KEY (`question_id`) REFERENCES `interview_question`(`id`) ON DELETE CASCADE;

-- 用户ID外键约束
ALTER TABLE `interview_answer` ADD CONSTRAINT `fk_interview_answer_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;


-- =====================================================
-- 回答评估表：保存系统对某次回答的分析结果与下一步决策
-- =====================================================
CREATE TABLE `answer_evaluation` (
    -- 评估ID，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评估ID',

    -- 所属面试会话ID，外键关联 interview_session 表
    `session_id` BIGINT NOT NULL COMMENT '面试会话ID',

    -- 对应问题ID，外键关联 interview_question 表
    `question_id` BIGINT NOT NULL COMMENT '问题ID',

    -- 对应回答ID，外键关联 interview_answer 表
    `answer_id` BIGINT NOT NULL COMMENT '回答ID',

    -- 回答得分，建议 0 到 100
    `score` INT NULL COMMENT '回答得分',

    -- 回答等级：GOOD=良好，NORMAL=一般，WEAK=薄弱
    `level` VARCHAR(20) NULL COMMENT '回答等级',

    -- 已命中的要点 JSON
    `matched_points_json` JSON NULL COMMENT '命中要点JSON',

    -- 缺失要点 JSON
    `missing_points_json` JSON NULL COMMENT '缺失要点JSON',

    -- 暴露的问题或风险 JSON
    `risk_points_json` JSON NULL COMMENT '风险点JSON',

    -- 下一步动作：FOLLOW_UP=追问，SWITCH_TOPIC=换题，NEXT_STAGE=进入下一阶段，FINISH=结束面试
    `next_action` VARCHAR(20) NULL COMMENT '下一步动作',

    -- 本次决策使用的候选问题池快照 JSON，用于审计与复盘
    `candidate_pool_snapshot_json` JSON NULL COMMENT '候选池快照JSON',

    -- 被选中的下一题ID，可为空（如结束面试时无下一题）
    `selected_next_question_id` BIGINT NULL COMMENT '选中的下一题ID',

    -- 简短评估说明
    `evaluation_text` TEXT NULL COMMENT '评估说明',

    -- 评估使用的模型名称
    `model_name` VARCHAR(100) NULL COMMENT '评估模型名称',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='回答评估表';

-- 会话ID索引，用于查询某会话的全部评估
CREATE INDEX `idx_answer_evaluation_session_id` ON `answer_evaluation`(`session_id`);

-- 问题ID索引，用于查询某问题的全部评估
CREATE INDEX `idx_answer_evaluation_question_id` ON `answer_evaluation`(`question_id`);

-- 回答ID索引，用于查询某回答的评估（评估与回答通常为 1:1）
CREATE INDEX `idx_answer_evaluation_answer_id` ON `answer_evaluation`(`answer_id`);

-- 会话ID外键约束
ALTER TABLE `answer_evaluation` ADD CONSTRAINT `fk_answer_evaluation_session_id`
    FOREIGN KEY (`session_id`) REFERENCES `interview_session`(`id`) ON DELETE CASCADE;

-- 问题ID外键约束
ALTER TABLE `answer_evaluation` ADD CONSTRAINT `fk_answer_evaluation_question_id`
    FOREIGN KEY (`question_id`) REFERENCES `interview_question`(`id`) ON DELETE CASCADE;

-- 回答ID外键约束
ALTER TABLE `answer_evaluation` ADD CONSTRAINT `fk_answer_evaluation_answer_id`
    FOREIGN KEY (`answer_id`) REFERENCES `interview_answer`(`id`) ON DELETE CASCADE;


-- =====================================================
-- 循环引用处理：为 interview_session 追加 current_question_id 外键。
-- 必须在 interview_question 表创建之后执行（被引用表需先存在）。
-- 问题被删除（硬删除兜底）时，会话的当前问题指针置空，
-- 避免指向不存在的题目；正常情况下问题走软删除，不受影响。
-- =====================================================
ALTER TABLE `interview_session` ADD CONSTRAINT `fk_interview_session_current_question_id`
    FOREIGN KEY (`current_question_id`) REFERENCES `interview_question`(`id`) ON DELETE SET NULL;
