-- =====================================================
-- V4__add_resume_vector_task_fields.sql
-- 为简历向量入库任务增加画像版本关联，避免旧版本结果覆盖新版本任务
-- =====================================================

ALTER TABLE `ai_task`
    ADD COLUMN `profile_version` INT NULL COMMENT '简历画像版本号，仅 RESUME_VECTORIZE 任务使用'
    AFTER `biz_id`;

CREATE INDEX `idx_vector_task_profile`
    ON `ai_task`(`task_type`, `biz_type`, `biz_id`, `profile_version`, `deleted`);
