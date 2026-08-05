package com.smartview.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 面试方向枚举
 *
 * 用于标识用户选择的面试方向，画像分析按方向分别生成。
 * 数据库存储字符串，业务代码应使用该枚举，取值与 MQ 契约、Web API 契约一致。
 *
 * @author SmartView Team
 * @since 2026-08-03
 */
@Getter
@AllArgsConstructor
public enum RoleDirection {

    /** Java 后端方向 */
    JAVA_BACKEND("JAVA_BACKEND", "Java 后端"),

    /** Agent 开发方向 */
    AGENT_DEVELOPMENT("AGENT_DEVELOPMENT", "Agent 开发");

    /** 方向代码，与数据库字段值一致 */
    private final String code;

    /** 方向描述，用于前端展示 */
    private final String description;

    /**
     * 根据方向代码获取枚举实例
     *
     * @param code 方向代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static RoleDirection fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (RoleDirection direction : values()) {
            if (direction.getCode().equals(code)) {
                return direction;
            }
        }
        return null;
    }
}
