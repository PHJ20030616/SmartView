-- =====================================================
-- V8__add_interview_answer_request_id_unique.sql
-- interview_answer.request_id 全局唯一索引。
--
-- 设计说明（docs/interview-policy.md 4.1）：
-- 1. 回答提交幂等依赖 request_id：同一 request_id 重复提交必须返回既有结果，
--    不能重复推进会话；全局唯一索引是幂等查询的数据库兜底。
-- 2. MySQL 唯一索引允许多个 NULL 值，历史遗留的 NULL request_id 记录不受影响，
--    因此无需先清理数据再建索引。
-- 3. 与 V7 已建的 (question_id, deleted) 唯一索引互补：前者约束"一题一份有效回答"，
--    后者约束"一次提交只落一条回答"，共同杜绝并发双写。
-- =====================================================
CREATE UNIQUE INDEX `uk_interview_answer_request_id`
    ON `interview_answer`(`request_id`);
