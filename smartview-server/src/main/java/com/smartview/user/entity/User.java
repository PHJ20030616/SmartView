package com.smartview.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体类
 *
 * 功能说明：
 * - 映射数据库 user 表
 * - 支持软删除（deleted 字段配合 @TableLogic）
 * - 支持字段自动填充（createdAt 和 updatedAt 配合 MyMetaObjectHandler）
 *
 * 技术要点：
 * - @TableName("user")：映射到 user 表
 * - @TableId(type = IdType.AUTO)：主键自增策略
 * - @TableLogic：标记 deleted 字段为逻辑删除字段（0=未删除，1=已删除）
 * - @TableField(fill = FieldFill.INSERT)：插入时自动填充 createdAt
 * - @TableField(fill = FieldFill.INSERT_UPDATE)：插入和更新时自动填充 updatedAt
 *
 * 字段映射：
 * - Java 驼峰命名自动映射到数据库下划线命名（由 MyBatis-Plus 配置）
 * - 例如：createdAt -> created_at, passwordHash -> password_hash
 *
 * @author SmartView Team
 * @since 2026-07-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user")
public class User {

    /**
     * 用户 ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 登录用户名，要求全局唯一（数据库唯一索引约束）
     */
    private String username;

    /**
     * 加密后的密码哈希值
     * 使用 BCrypt 算法加密，禁止存储明文密码
     */
    private String passwordHash;

    /**
     * 用户昵称，用于前端展示
     */
    private String nickname;

    /**
     * 用户邮箱，可选字段
     * 如果填写则要求唯一（仅对未删除用户生效，通过数据库组合唯一索引实现）
     */
    private String email;

    /**
     * 用户手机号，可选字段
     * 如果填写则要求唯一（仅对未删除用户生效，通过数据库组合唯一索引实现）
     */
    private String phone;

    /**
     * 用户状态：ACTIVE=正常，DISABLED=禁用，LOCKED=锁定
     * 禁用和锁定的用户无法登录系统
     */
    private String status;

    /**
     * 最近一次登录时间，用于统计用户活跃度
     */
    private LocalDateTime lastLoginAt;

    /**
     * 记录创建时间
     * 由 MyMetaObjectHandler 在插入时自动填充，业务代码无需手动设置
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间
     * 由 MyMetaObjectHandler 在插入和更新时自动填充，业务代码无需手动设置
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 软删除标记：0=未删除，1=已删除
     * 配合 @TableLogic 注解实现逻辑删除：
     * - 查询时自动添加 deleted=0 条件
     * - 调用 deleteById 时执行 UPDATE 而非 DELETE
     */
    @TableLogic
    private Integer deleted;
}
