-- =====================================================
-- V2__create_resume_and_ai_task_tables.sql
-- 创建简历相关表及 AI 任务表
-- 作者：SmartView Team
-- 日期：2026-07-23
-- =====================================================

-- =====================================================
-- 简历文件表：存储用户上传的简历文件元数据
-- =====================================================
CREATE TABLE `resume_file` (
    -- 简历文件唯一标识，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '简历文件ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 用户上传时的原始文件名，用于前端展示和下载时恢复原文件名
    `original_filename` VARCHAR(255) NOT NULL COMMENT '原始文件名',

    -- MinIO 中的文件对象 Key，用于从对象存储检索文件
    -- 格式示例：resumes/{user_id}/{uuid}.pdf
    `object_key` VARCHAR(500) NOT NULL COMMENT 'MinIO对象Key',

    -- 文件 SHA-256 哈希值，用于去重检测和文件完整性校验
    `file_hash` VARCHAR(64) NOT NULL COMMENT '文件哈希值',

    -- 文件大小，单位字节，用于存储配额管理和前端展示
    `file_size` BIGINT NOT NULL COMMENT '文件大小(字节)',

    -- 文件 MIME 类型，第一版主要为 application/pdf，后续可能支持 Word 等格式
    `mime_type` VARCHAR(100) NOT NULL COMMENT 'MIME类型',

    -- 解析状态：PENDING=待解析，PROCESSING=解析中，SUCCESS=解析成功，FAILED=解析失败
    `parse_status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态',

    -- 对应的 AI 解析任务 ID，关联 ai_task 表的 task_id
    `parse_task_id` VARCHAR(64) NULL COMMENT 'AI解析任务ID',

    -- 解析失败时的错误信息，用于向用户展示失败原因
    `error_message` TEXT NULL COMMENT '解析失败原因',

    -- 文件上传时间，用于排序和统计
    `uploaded_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='简历文件表';

-- 用户ID索引，用于查询某用户的所有简历
CREATE INDEX `idx_user_id` ON `resume_file`(`user_id`);

-- 解析状态索引，用于查询待处理或失败的简历
CREATE INDEX `idx_parse_status` ON `resume_file`(`parse_status`);

-- 解析任务ID索引，用于通过任务ID反查简历文件
CREATE INDEX `idx_parse_task_id` ON `resume_file`(`parse_task_id`);

-- 文件哈希索引，用于文件去重检测
CREATE INDEX `idx_file_hash` ON `resume_file`(`file_hash`);

-- 软删除标记索引
CREATE INDEX `idx_deleted` ON `resume_file`(`deleted`);

-- 上传时间索引，用于按时间排序
CREATE INDEX `idx_uploaded_at` ON `resume_file`(`uploaded_at`);

-- 用户ID外键约束
ALTER TABLE `resume_file` ADD CONSTRAINT `fk_resume_file_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;


-- =====================================================
-- 简历画像表：存储 AI 解析后的结构化简历数据
-- =====================================================
CREATE TABLE `resume_profile` (
    -- 简历画像唯一标识，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '简历画像ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 对应的简历文件ID，外键关联 resume_file 表
    `resume_file_id` BIGINT NOT NULL COMMENT '简历文件ID',

    -- 候选人姓名，从简历中提取
    `candidate_name` VARCHAR(100) NULL COMMENT '候选人姓名',

    -- 联系方式 JSON，包含手机号、邮箱、微信等
    -- 示例：{"phone": "13800138000", "email": "example@example.com", "wechat": "example"}
    `contact_info_json` JSON NULL COMMENT '联系方式JSON',

    -- 教育经历 JSON，包含学校、专业、学历、时间等
    -- 示例：[{"school": "清华大学", "major": "计算机科学", "degree": "本科", "startDate": "2015-09", "endDate": "2019-06"}]
    `education_json` JSON NULL COMMENT '教育经历JSON',

    -- 工作经历 JSON，包含公司、职位、职责、时间等
    -- 示例：[{"company": "字节跳动", "position": "Java开发工程师", "startDate": "2019-07", "endDate": "2022-03"}]
    `work_experience_json` JSON NULL COMMENT '工作经历JSON',

    -- 项目经历 JSON，包含项目名称、职责、技术栈等
    -- 示例：[{"projectName": "电商平台", "role": "后端负责人", "techStack": ["Spring Boot", "MySQL"]}]
    `project_experience_json` JSON NULL COMMENT '项目经历JSON',

    -- 技能栈 JSON，包含编程语言、框架、工具等
    -- 示例：{"languages": ["Java", "Python"], "frameworks": ["Spring", "Django"], "tools": ["Git", "Docker"]}
    `skills_json` JSON NULL COMMENT '技能栈JSON',

    -- PDF 提取或 OCR 得到的简历原文，用于审计和二次解析
    `raw_text` LONGTEXT NULL COMMENT '简历原文',

    -- 完整结构化解析结果 JSON，包含上述所有字段的汇总
    `profile_json` JSON NULL COMMENT '完整画像JSON',

    -- 用户确认状态：UNCONFIRMED=未确认，CONFIRMED=已确认
    -- 用户可以编辑 AI 解析结果后确认
    `confirm_status` VARCHAR(20) NOT NULL DEFAULT 'UNCONFIRMED' COMMENT '确认状态',

    -- 用户确认时间
    `confirmed_at` DATETIME NULL COMMENT '确认时间',

    -- 画像版本号，用户重新上传或重新解析时递增
    -- 同一个 resume_file_id 可能有多个版本的画像
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='简历画像表';

-- 用户ID索引，用于查询某用户的所有简历画像
CREATE INDEX `idx_user_id` ON `resume_profile`(`user_id`);

-- 简历文件ID索引，用于查询某简历文件的所有版本画像
CREATE INDEX `idx_resume_file_id` ON `resume_profile`(`resume_file_id`);

-- 确认状态索引，用于查询待确认的画像
CREATE INDEX `idx_confirm_status` ON `resume_profile`(`confirm_status`);

-- 候选人姓名索引，用于按姓名搜索
CREATE INDEX `idx_candidate_name` ON `resume_profile`(`candidate_name`);

-- 软删除标记索引
CREATE INDEX `idx_deleted` ON `resume_profile`(`deleted`);

-- 简历文件ID和版本号联合唯一索引，防止同一文件产生重复版本号
CREATE UNIQUE INDEX `uk_resume_file_id_version` ON `resume_profile`(`resume_file_id`, `version`);

-- 用户ID外键约束
ALTER TABLE `resume_profile` ADD CONSTRAINT `fk_resume_profile_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;

-- 简历文件ID外键约束
ALTER TABLE `resume_profile` ADD CONSTRAINT `fk_resume_profile_resume_file_id`
    FOREIGN KEY (`resume_file_id`) REFERENCES `resume_file`(`id`) ON DELETE CASCADE;


-- =====================================================
-- AI 任务表：统一管理所有异步 AI 任务的生命周期
-- =====================================================
CREATE TABLE `ai_task` (
    -- 主键ID，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',

    -- 业务任务ID，全局唯一字符串，建议使用 UUID
    -- 用于跨服务追踪任务状态
    `task_id` VARCHAR(64) NOT NULL COMMENT '业务任务ID',

    -- 所属用户ID，外键关联 user 表
    `user_id` BIGINT NOT NULL COMMENT '所属用户ID',

    -- 任务类型：RESUME_PARSE=简历解析，PROFILE_ANALYZE=画像分析，REPORT_GENERATE=报告生成，CLEANUP=清理任务
    `task_type` VARCHAR(50) NOT NULL COMMENT '任务类型',

    -- 任务状态：PENDING=待处理，PROCESSING=处理中，SUCCESS=成功，FAILED=失败，RETRYING=重试中
    `task_status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态',

    -- 关联业务类型：RESUME_FILE=简历文件，INTERVIEW_SESSION=面试会话
    `biz_type` VARCHAR(50) NULL COMMENT '业务类型',

    -- 关联业务ID，指向对应业务表的主键
    `biz_id` BIGINT NULL COMMENT '业务ID',

    -- 投递给 AI 服务的请求 JSON，用于审计和重试
    `request_payload_json` JSON NULL COMMENT '请求载荷JSON',

    -- AI 服务返回的结果 JSON
    `result_payload_json` JSON NULL COMMENT '结果载荷JSON',

    -- 失败原因，记录错误堆栈或错误消息
    `error_message` TEXT NULL COMMENT '失败原因',

    -- 当前重试次数，每次重试递增
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',

    -- 最大重试次数，超过后任务标记为最终失败
    `max_retry` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',

    -- 链路追踪ID，用于分布式链路追踪（如 SkyWalking、Zipkin）
    `trace_id` VARCHAR(64) NULL COMMENT '链路追踪ID',

    -- MQ 消息类型，用于标识消息的业务含义
    `message_type` VARCHAR(50) NULL COMMENT 'MQ消息类型',

    -- MQ 消息 schema 版本，用于消息格式兼容性管理
    `schema_version` VARCHAR(20) NULL COMMENT 'Schema版本',

    -- 任务开始时间
    `started_at` DATETIME NULL COMMENT '开始时间',

    -- 任务结束时间
    `finished_at` DATETIME NULL COMMENT '结束时间',

    -- 记录创建时间
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI任务表';

-- 任务ID唯一索引，确保全局唯一
CREATE UNIQUE INDEX `uk_task_id` ON `ai_task`(`task_id`);

-- 用户ID索引，用于查询某用户的所有任务
CREATE INDEX `idx_user_id` ON `ai_task`(`user_id`);

-- 任务类型索引，用于按类型查询任务
CREATE INDEX `idx_task_type` ON `ai_task`(`task_type`);

-- 任务状态索引，用于查询待处理或失败的任务
CREATE INDEX `idx_task_status` ON `ai_task`(`task_status`);

-- 业务类型和业务ID组合索引，用于反查业务关联的任务
CREATE INDEX `idx_biz_type_biz_id` ON `ai_task`(`biz_type`, `biz_id`);

-- 链路追踪ID索引，用于分布式追踪
CREATE INDEX `idx_trace_id` ON `ai_task`(`trace_id`);

-- 软删除标记索引
CREATE INDEX `idx_deleted` ON `ai_task`(`deleted`);

-- 创建时间索引，用于按时间查询或清理历史数据
CREATE INDEX `idx_created_at` ON `ai_task`(`created_at`);

-- 用户ID外键约束
ALTER TABLE `ai_task` ADD CONSTRAINT `fk_ai_task_user_id`
    FOREIGN KEY (`user_id`) REFERENCES `user`(`id`) ON DELETE CASCADE;
