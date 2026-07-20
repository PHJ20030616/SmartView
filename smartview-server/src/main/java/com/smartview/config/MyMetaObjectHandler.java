package com.smartview.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充处理器
 *
 * 功能说明：
 * - 在执行插入操作时，自动填充 createdAt 和 updatedAt 字段
 * - 在执行更新操作时，自动填充 updatedAt 字段
 *
 * 技术实现：
 * - 实体类字段需使用 @TableField(fill = FieldFill.INSERT/UPDATE) 注解
 * - 本处理器在 SQL 执行前自动注入字段值
 * - 避免业务代码手动设置时间字段，减少重复代码和人为错误
 *
 * 支持的填充策略：
 * - FieldFill.INSERT：仅在插入时填充
 * - FieldFill.UPDATE：仅在更新时填充
 * - FieldFill.INSERT_UPDATE：插入和更新时都填充
 *
 * 注意事项：
 * - 如果业务代码已显式设置字段值，则不会被覆盖
 * - 本处理器仅在应用层生效，直接执行 SQL 语句不会触发
 * - 时间字段使用 LocalDateTime 类型，适配 MySQL 的 DATETIME 类型
 *
 * @author SmartView Team
 * @since 2026-07-20
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /**
     * 插入操作时自动填充字段
     *
     * 填充逻辑：
     * - createdAt：记录创建时间，使用当前时间
     * - updatedAt：记录更新时间，使用当前时间
     *
     * @param metaObject 元数据对象，包含实体类的字段信息
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();

        // 严格填充：仅当字段值为 null 时才填充，避免覆盖业务代码设置的值
        this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    /**
     * 更新操作时自动填充字段
     *
     * 填充逻辑：
     * - updatedAt：记录更新时间，使用当前时间
     * - createdAt 不在更新时填充，保持原值不变
     *
     * @param metaObject 元数据对象，包含实体类的字段信息
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();

        // 严格填充：仅当字段值为 null 时才填充
        this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }
}
