-- =====================================================
-- V3__normalize_index_names.sql
-- 统一历史迁移中重复的索引名称
-- =====================================================

-- V1、V2 已经在部分环境执行，不能直接修改原迁移文件；通过新版本迁移完成索引重命名，
-- 既保持 Flyway checksum 稳定，也避免不同数据表使用相同索引名时的数据库兼容问题。
ALTER TABLE `user` RENAME INDEX `uk_username` TO `uk_user_username`;
ALTER TABLE `user` RENAME INDEX `uk_email_deleted` TO `uk_user_email_deleted`;
ALTER TABLE `user` RENAME INDEX `uk_phone_deleted` TO `uk_user_phone_deleted`;
ALTER TABLE `user` RENAME INDEX `idx_status` TO `idx_user_status`;
ALTER TABLE `user` RENAME INDEX `idx_deleted` TO `idx_user_deleted`;
ALTER TABLE `user` RENAME INDEX `idx_created_at` TO `idx_user_created_at`;

ALTER TABLE `resume_file` RENAME INDEX `idx_user_id` TO `idx_resume_file_user_id`;
ALTER TABLE `resume_file` RENAME INDEX `idx_parse_status` TO `idx_resume_file_parse_status`;
ALTER TABLE `resume_file` RENAME INDEX `idx_parse_task_id` TO `idx_resume_file_parse_task_id`;
ALTER TABLE `resume_file` RENAME INDEX `idx_file_hash` TO `idx_resume_file_file_hash`;
ALTER TABLE `resume_file` RENAME INDEX `idx_deleted` TO `idx_resume_file_deleted`;
ALTER TABLE `resume_file` RENAME INDEX `idx_uploaded_at` TO `idx_resume_file_uploaded_at`;

ALTER TABLE `resume_profile` RENAME INDEX `idx_user_id` TO `idx_resume_profile_user_id`;
ALTER TABLE `resume_profile` RENAME INDEX `idx_resume_file_id` TO `idx_resume_profile_resume_file_id`;
ALTER TABLE `resume_profile` RENAME INDEX `idx_confirm_status` TO `idx_resume_profile_confirm_status`;
ALTER TABLE `resume_profile` RENAME INDEX `idx_candidate_name` TO `idx_resume_profile_candidate_name`;
ALTER TABLE `resume_profile` RENAME INDEX `idx_deleted` TO `idx_resume_profile_deleted`;
ALTER TABLE `resume_profile` RENAME INDEX `uk_resume_file_id_version`
    TO `uk_resume_profile_file_id_version`;

ALTER TABLE `ai_task` RENAME INDEX `idx_user_id` TO `idx_ai_task_user_id`;
ALTER TABLE `ai_task` RENAME INDEX `idx_task_type` TO `idx_ai_task_task_type`;
ALTER TABLE `ai_task` RENAME INDEX `idx_task_status` TO `idx_ai_task_task_status`;
ALTER TABLE `ai_task` RENAME INDEX `idx_biz_type_biz_id` TO `idx_ai_task_biz_type_biz_id`;
ALTER TABLE `ai_task` RENAME INDEX `idx_trace_id` TO `idx_ai_task_trace_id`;
ALTER TABLE `ai_task` RENAME INDEX `idx_deleted` TO `idx_ai_task_deleted`;
ALTER TABLE `ai_task` RENAME INDEX `idx_created_at` TO `idx_ai_task_created_at`;
