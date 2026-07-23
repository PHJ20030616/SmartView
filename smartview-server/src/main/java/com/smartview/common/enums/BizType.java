package com.smartview.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务类型枚举
 *
 * 用于标识 AI 任务关联的业务实体类型
 * 配合 biz_id 字段使用，实现任务与业务数据的双向关联
 *
 * 业务规则：
 * - biz_type + biz_id 唯一标识一个业务实体
 * - 通过业务实体可以反查所有关联的 AI 任务
 * - 任务完成后可以根据 biz_type 和 biz_id 更新对应业务表的状态
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Getter
@AllArgsConstructor
public enum BizType {

    /**
     * 简历文件
     * biz_id 指向 resume_file 表的主键
     * 用于简历解析任务
     */
    RESUME_FILE("RESUME_FILE", "简历文件"),

    /**
     * 面试会话
     * biz_id 指向 interview_session 表的主键（后续扩展）
     * 用于面试分析、报告生成任务
     */
    INTERVIEW_SESSION("INTERVIEW_SESSION", "面试会话");

    /**
     * 业务类型代码，与数据库字段值一致
     */
    private final String code;

    /**
     * 业务类型描述，用于前端展示
     */
    private final String description;

    /**
     * 根据业务类型代码获取枚举实例
     *
     * @param code 业务类型代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static BizType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (BizType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
