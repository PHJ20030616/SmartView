package com.smartview.interview.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 面试问题状态枚举。
 *
 * 功能说明：
 * - 标识每道题的生命周期状态，与 interview_question.status 存储值一致
 * - ASKED：已提问等待回答；ANSWERED：已回答；SKIPPED：已跳过
 *
 * 首题创建后即为 ASKED（等待用户回答）。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Getter
@AllArgsConstructor
public enum InterviewQuestionStatus {

    /** 已提问，等待用户回答 */
    ASKED("ASKED", "已提问"),

    /** 已回答 */
    ANSWERED("ANSWERED", "已回答"),

    /** 已跳过 */
    SKIPPED("SKIPPED", "已跳过");

    /** 状态代码，与数据库字段值一致 */
    private final String code;

    /** 状态描述 */
    private final String description;

    /**
     * 根据状态代码获取枚举实例。
     *
     * @param code 状态代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static InterviewQuestionStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (InterviewQuestionStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
