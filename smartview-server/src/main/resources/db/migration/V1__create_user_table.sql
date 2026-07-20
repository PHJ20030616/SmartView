-- =====================================================
-- V1__create_user_table.sql
-- 创建用户表及相关索引
-- 作者：SmartView Team
-- 日期：2026-07-20
-- =====================================================

-- 用户表：存储系统用户的基本信息和认证凭据
CREATE TABLE `user` (
    -- 用户唯一标识，主键，自增
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',

    -- 登录用户名，要求全局唯一，用于用户登录认证
    `username` VARCHAR(50) NOT NULL COMMENT '登录用户名',

    -- 加密后的密码哈希值，禁止存储明文密码
    -- 使用 BCrypt 算法加密，长度固定为 60 字符，预留 255 以兼容未来算法升级
    `password_hash` VARCHAR(255) NOT NULL COMMENT '加密后的密码',

    -- 用户昵称，用于前端展示，允许重复
    `nickname` VARCHAR(100) NULL COMMENT '用户昵称',

    -- 用户邮箱，可选字段，用于找回密码、通知等场景
    -- 如果填写则要求唯一（仅对未删除用户生效）
    `email` VARCHAR(100) NULL COMMENT '用户邮箱',

    -- 用户手机号，可选字段，用于短信验证、通知等场景
    -- 如果填写则要求唯一（仅对未删除用户生效）
    `phone` VARCHAR(20) NULL COMMENT '用户手机号',

    -- 用户状态：ACTIVE=正常，DISABLED=禁用，LOCKED=锁定
    -- 禁用和锁定的用户无法登录系统
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '用户状态',

    -- 最近一次登录时间，用于统计用户活跃度
    `last_login_at` DATETIME NULL COMMENT '最近登录时间',

    -- 记录创建时间，不可修改
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，每次更新自动刷新
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    -- 软删除标记：0=未删除，1=已删除
    -- 采用软删除以保留历史数据，避免外键关联断裂
    `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记',

    -- 主键约束
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 用户名唯一索引
-- 登录时通过用户名快速查找用户
CREATE UNIQUE INDEX `uk_username` ON `user`(`username`);

-- 邮箱唯一索引（仅对未删除用户生效）
-- 使用部分索引确保软删除后的邮箱可以被新用户重复使用
-- 注意：MySQL 8.0.13+ 支持函数索引和过滤索引，但语法有限制
-- 为兼容性考虑，此处使用组合唯一索引 (email, deleted)
-- 应用层需确保 email 为 NULL 或 deleted=0 时才校验唯一性
CREATE UNIQUE INDEX `uk_email_deleted` ON `user`(`email`, `deleted`);

-- 手机号唯一索引（仅对未删除用户生效）
-- 同邮箱索引，使用组合唯一索引保证软删除场景下的数据一致性
CREATE UNIQUE INDEX `uk_phone_deleted` ON `user`(`phone`, `deleted`);

-- 用户状态索引
-- 用于快速查询特定状态的用户列表（如批量查询所有被禁用的用户）
CREATE INDEX `idx_status` ON `user`(`status`);

-- 软删除标记索引
-- 用于快速过滤已删除/未删除的用户，MyBatis-Plus 的逻辑删除功能依赖此索引
CREATE INDEX `idx_deleted` ON `user`(`deleted`);

-- 创建时间索引
-- 用于按注册时间排序或统计分析
CREATE INDEX `idx_created_at` ON `user`(`created_at`);
