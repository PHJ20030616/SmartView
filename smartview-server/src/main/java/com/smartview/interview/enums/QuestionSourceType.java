package com.smartview.interview.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 面试问题来源类型枚举。
 *
 * 功能说明：
 * - 标识一道题的题目出处，与 interview_question.source_type 存储值一致
 * - 用于溯源与复盘：问题可能来自八股知识库、面经案例、简历项目，或综合多个来源
 * - 由 FastAPI 出题时判定并返回，Spring 原样落库
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Getter
@AllArgsConstructor
public enum QuestionSourceType {

    /** 来源为八股知识库（interview_knowledge_base） */
    KNOWLEDGE_BASE("KNOWLEDGE_BASE", "八股知识库"),

    /** 来源为面经案例（interview_experience_cases） */
    EXPERIENCE_CASE("EXPERIENCE_CASE", "面经案例"),

    /** 来源为简历项目（resume_profile_chunks） */
    RESUME_PROJECT("RESUME_PROJECT", "简历项目"),

    /** 混合来源：综合知识库、面经与简历等多个材料生成 */
    MIXED("MIXED", "混合来源");

    /** 来源代码，与数据库字段值一致 */
    private final String code;

    /** 来源描述 */
    private final String description;

    /**
     * 根据来源代码获取枚举实例。
     *
     * @param code 来源代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static QuestionSourceType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (QuestionSourceType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
