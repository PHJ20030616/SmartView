-- =====================================================
-- V5__add_resume_vector_operation.sql
-- 为向量任务记录操作类型，区分写入当前版本和删除旧向量。
-- =====================================================

ALTER TABLE `ai_task`
    ADD COLUMN `operation` VARCHAR(20) NULL
        COMMENT '向量任务操作类型：UPSERT 或 DELETE；非向量任务为空'
        AFTER `profile_version`;

CREATE INDEX `idx_vector_task_operation`
    ON `ai_task`(`task_type`, `biz_type`, `biz_id`, `profile_version`, `operation`, `deleted`);
