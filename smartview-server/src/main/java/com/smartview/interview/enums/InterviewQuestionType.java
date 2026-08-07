package com.smartview.interview.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 面试问题类型枚举。
 *
 * 功能说明：
 * - 标识系统提出的每道题的类型，与 interview_question.question_type 存储值一致
 * - OPENING：阶段开场题；FOLLOW_UP：对上一题的追问；
 *   SWITCH_TOPIC：同阶段切换新主题；STAGE_ENTRY：进入下一阶段的入口题
 *
 * 首题固定为 OPENING（BASIC 阶段开场），后续题目类型由 Task 5.4 的
 * StagePolicyEngine 决策生成。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Getter
@AllArgsConstructor
public enum InterviewQuestionType {

    /** 开场题：某阶段/主题的第一道题 */
    OPENING("OPENING", "开场题"),

    /** 追问：基于上一题回答的深入提问 */
    FOLLOW_UP("FOLLOW_UP", "追问"),

    /** 换题：同阶段切换到新的主题 */
    SWITCH_TOPIC("SWITCH_TOPIC", "换题"),

    /** 阶段入口题：进入下一阶段的第一道题 */
    STAGE_ENTRY("STAGE_ENTRY", "阶段入口题");

    /** 类型代码，与数据库字段值一致 */
    private final String code;

    /** 类型描述 */
    private final String description;

    /**
     * 根据类型代码获取枚举实例。
     *
     * @param code 类型代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static InterviewQuestionType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (InterviewQuestionType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return null;
    }
}
