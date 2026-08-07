package com.smartview.interview.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 面试会话生命周期状态枚举。
 *
 * 功能说明：
 * - 标识一次模拟面试的会话状态，与 interview_session.status 存储值一致
 * - 状态机：CREATED（已创建未开始）→ IN_PROGRESS（面试中）→ REPORTING/COMPLETED；
 *   异常或用户主动放弃时进入 FAILED/CANCELLED
 *
 * 业务规则：
 * - 会话创建后先置 CREATED，写入首题并更新 current_question_id 后进入 IN_PROGRESS
 * - 会话终态与报告生成状态相互独立：报告失败不得把已结束的面试改成失败会话
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Getter
@AllArgsConstructor
public enum InterviewSessionStatus {

    /** 已创建未开始：会话已落库但尚无题目 */
    CREATED("CREATED", "已创建未开始"),

    /** 面试中：当前有等待回答的问题 */
    IN_PROGRESS("IN_PROGRESS", "面试中"),

    /** 报告生成中：面试已结束，正在异步生成报告 */
    REPORTING("REPORTING", "报告生成中"),

    /** 已完成：面试结束且报告已生成 */
    COMPLETED("COMPLETED", "已完成"),

    /** 已取消：用户主动放弃且不生成报告 */
    CANCELLED("CANCELLED", "已取消"),

    /** 异常失败：流程异常导致会话无法继续 */
    FAILED("FAILED", "异常失败");

    /** 状态代码，与数据库字段值一致 */
    private final String code;

    /** 状态描述，用于前端展示 */
    private final String description;

    /**
     * 根据状态代码获取枚举实例。
     *
     * @param code 状态代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static InterviewSessionStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (InterviewSessionStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return null;
    }
}
