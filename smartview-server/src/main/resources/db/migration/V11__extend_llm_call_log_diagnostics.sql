-- =====================================================
-- V11__extend_llm_call_log_diagnostics.sql
-- 扩充 LLM 调用日志的诊断字段与业务归因索引。
--
-- 背景（由线上 141 条调用记录复盘得出）：V10 建表时只记录了"结果如何"，
-- 没有记录"为什么是这个结果"，导致三类问题无法回答：
-- 1. report_generate 有 11/20 次失败、错误码统一是 LLM_INVALID_JSON，
--    但看不出失败与"输出被 max_tokens 截断"的关系——只能靠 token_output
--    是否贴近 max_tokens 去猜；
-- 2. 切到 OpenCode 网关后 resume_parse 连续 5 次失败（HTTP 400，缺会话头），
--    状态码被 raise_for_status 丢掉，日志与埋点都只剩"服务暂时不可用"；
-- 3. MQ 任务重试与提示词修复重试混在 retry_attempt 一列，无法区分
--    "同一次任务内的第 2 次提示"和"整个任务的第 2 轮重试"。
--
-- 因此本次新增三列，语义互不重叠：
-- - http_status：上游 HTTP 状态码，区分 429 限流 / 5xx 上游故障 / 其余 4xx 请求被拒；
-- - finish_reason：上游停止原因，length 即"输出被截断"，是 JSON 解析失败的头号原因；
-- - attempt_no：任务重试轮次（来自 MQ 消息的 retryCount），与 retry_attempt
--   （同一次任务内的提示词修复序号）共同还原完整的重试关系。
--
-- 三列均可空（历史数据不回填），且都不参与任何业务约束，仅作诊断维度。
-- =====================================================

-- 三列拆成三条 ALTER：MySQL 与 H2（迁移测试用的 MySQL 兼容模式）对
-- "单条 ALTER 里多个 ADD COLUMN + COMMENT + AFTER"的支持不一致，
-- 拆开后两种库都能执行，也让每一列的意图更容易对照阅读。
ALTER TABLE `llm_call_log`
    -- 上游 HTTP 状态码；成功为 200，失败时用于区分限流/上游故障/请求被拒
    ADD COLUMN `http_status` INT NULL COMMENT '上游HTTP状态码' AFTER `retry_attempt`;

ALTER TABLE `llm_call_log`
    -- 上游停止原因；length=输出被 max_tokens 截断（JSON 解析失败的头号原因）
    ADD COLUMN `finish_reason` VARCHAR(30) NULL COMMENT '上游停止原因' AFTER `http_status`;

ALTER TABLE `llm_call_log`
    -- MQ 任务重试轮次，0=首次处理；与 retry_attempt（提示词修复序号）是两个维度
    ADD COLUMN `attempt_no` INT NOT NULL DEFAULT 0 COMMENT '任务重试轮次' AFTER `finish_reason`;

-- 业务归因查询：按会话/简历聚合 LLM 调用成本（SELECT ... WHERE biz_type=? AND biz_id=?）。
-- 本表是只追加的技术日志，写入量远低于业务表，多一个索引的写入代价可忽略；
-- 而没有它，"哪个会话最贵、哪个场景在某类业务对象上失败率异常"只能全表扫描。
CREATE INDEX `idx_llm_call_log_biz_created`
    ON `llm_call_log`(`biz_type`, `biz_id`, `created_at`);
