-- =====================================================
-- V10__create_llm_call_log_table.sql
-- 创建 LLM 调用日志表：记录每一次大模型调用的场景、用量与耗时。
--
-- 设计说明（与 plan_1.1.md §5.1 保持一致）：
-- 1. 这是**技术可观测表**，不是业务主表。写入方是 FastAPI（AI 服务），
--    读取方是 Spring Boot。AGENTS.md 已将其登记为"FastAPI 不直接写业务主表"
--    的明确例外，边界定义见 plan_1.1.md §5.4：FastAPI 只允许写这一张表，
--    不得读写 user、resume_*、interview_*、answer_evaluation、interview_report。
-- 2. **不建任何外键**：日志写入频繁，且用户或画像被删除时不应级联清掉历史
--    观测数据（那会让成本与成功率统计出现空洞）。biz_type/biz_id 只作弱引用维度。
-- 3. **不落 prompt 与响应全文**：简历原文与用户回答属敏感数据，只保存
--    request_hash（sha256）与请求字符数，既能做"是否同一请求"的归因，又不构成泄露面。
-- 4. 无 deleted 软删除列：调用日志只追加不修改，保留策略留给后续清理任务。
-- 5. 索引按实际查询形态建立：时间倒序列表 (created_at)、按场景与 prompt 版本
--    聚合 (scene, prompt_version, created_at)、按链路追踪定位 (trace_id)。
-- =====================================================

CREATE TABLE `llm_call_log` (
    -- 主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',

    -- 链路追踪ID，与 Spring Boot 的 X-Trace-Id 关联，用于还原一次完整面试的调用链
    `trace_id` VARCHAR(64) NULL COMMENT '链路追踪ID',

    -- 调用场景：question_generate=出题，evaluate=回答评估，report_generate=报告与参考答案，
    -- profile_analyze=画像分析，resume_parse=简历结构化
    `scene` VARCHAR(50) NOT NULL COMMENT '调用场景',

    -- 业务对象类型（弱引用，本期不填充，Phase 13 需要按会话归因时启用）
    `biz_type` VARCHAR(50) NULL COMMENT '业务对象类型',

    -- 业务对象ID（弱引用，无外键约束，允许悬空）
    `biz_id` BIGINT NULL COMMENT '业务对象ID',

    -- 模型提供方
    `provider` VARCHAR(30) NOT NULL DEFAULT 'deepseek' COMMENT '模型提供方',

    -- 实际使用的模型名
    `model` VARCHAR(100) NOT NULL COMMENT '模型名称',

    -- prompt 标识（Phase 14 引入按场景的 prompt 文件后填充）
    `prompt_key` VARCHAR(100) NULL COMMENT 'prompt标识',

    -- prompt 版本，用于把指标变化归因到具体 prompt 迭代
    `prompt_version` VARCHAR(50) NULL COMMENT 'prompt版本',

    -- 提示词 sha256，用于判断是否同一请求；不保存提示词全文
    `request_hash` CHAR(64) NULL COMMENT '提示词哈希',

    -- 提示词字符数，用于观察输入规模与成本关系
    `request_chars` INT NULL COMMENT '提示词字符数',

    -- 采样温度
    `temperature` DECIMAL(3,2) NULL COMMENT '采样温度',

    -- 最大输出 token 数
    `max_tokens` INT NULL COMMENT '最大输出token数',

    -- 输入 token 数
    `token_input` INT NULL COMMENT '输入token数',

    -- 输出 token 数
    `token_output` INT NULL COMMENT '输出token数',

    -- 总 token 数
    `token_total` INT NULL COMMENT '总token数',

    -- 调用耗时（毫秒），含网络与模型生成时间
    `latency_ms` INT NOT NULL COMMENT '调用耗时(毫秒)',

    -- 调用结果：SUCCESS=成功，FAILED=失败
    `status` VARCHAR(20) NOT NULL COMMENT '调用结果',

    -- 失败时的错误码，与 AI 服务的 AppError.code 一致
    `error_code` VARCHAR(50) NULL COMMENT '错误码',

    -- 失败时的错误信息（截断至 500 字符，与列宽一致）
    `error_message` VARCHAR(500) NULL COMMENT '错误信息',

    -- 修复重试序号：0=首次调用，1=带修复上下文的第二次调用
    `retry_attempt` INT NOT NULL DEFAULT 0 COMMENT '修复重试序号',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM调用日志表';

-- 时间倒序列表查询（看板默认视图）
CREATE INDEX `idx_llm_call_log_created_at` ON `llm_call_log`(`created_at`);

-- 按场景 + prompt 版本聚合统计（成功率、P95、token 成本）
CREATE INDEX `idx_llm_call_log_scene_prompt_created` ON `llm_call_log`(`scene`, `prompt_version`, `created_at`);

-- 按链路追踪还原一次完整面试的全部 LLM 调用
CREATE INDEX `idx_llm_call_log_trace_id` ON `llm_call_log`(`trace_id`);
