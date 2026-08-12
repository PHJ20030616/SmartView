package com.smartview.report.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 参考答案类型枚举。
 *
 * 与 reference_answer.answer_type 存储值及 web-api 契约一致；按题目阶段映射：
 * BASIC→基础题关键要点，PROJECT→项目题回答结构，SCENARIO→场景题答题框架。
 */
@Getter
@AllArgsConstructor
public enum ReferenceAnswerType {

    /** 基础题关键要点 */
    BASIC_KEY_POINTS("BASIC_KEY_POINTS", "基础题关键要点"),

    /** 项目题回答结构 */
    PROJECT_STRUCTURE("PROJECT_STRUCTURE", "项目题回答结构"),

    /** 场景题答题框架 */
    SCENARIO_FRAMEWORK("SCENARIO_FRAMEWORK", "场景题答题框架");

    private final String code;
    private final String description;

    /** 根据类型代码获取枚举实例，不存在返回 null */
    public static ReferenceAnswerType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ReferenceAnswerType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
