package com.smartview.interview.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 面试阶段枚举。
 *
 * 功能说明：
 * - 标识会话当前所处的内部阶段，与 interview_session.current_stage 存储值一致
 * - 阶段顺序即面试推进顺序：BASIC → PROJECT → SCENARIO → REPORT（报告阶段非出题阶段）
 * - 与 docs/interview-policy.md 2.1 阶段定义保持一致
 *
 * 注意：数据库以字符串存储，本枚举是当前约定的权威来源；后续若 Task 5.4
 * StagePolicyEngine 统一枚举定义，本类可作为其迁移基线。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Getter
@AllArgsConstructor
public enum InterviewStage {

    /** 基础八股阶段：考察基础知识扎实度，题型为概念/原理/对比/场景判断 */
    BASIC("BASIC", "基础八股"),

    /** 项目追问阶段：考察项目经历真实性与深度，题型为技术选型/架构/难点/优化 */
    PROJECT("PROJECT", "项目追问"),

    /** 场景设计阶段：考察综合应用与设计能力，题型为方案设计/权衡/落地/改进 */
    SCENARIO("SCENARIO", "场景设计"),

    /** 报告阶段：面试结束后的报告生成期，不产题 */
    REPORT("REPORT", "报告阶段");

    /** 阶段代码，与数据库字段值一致 */
    private final String code;

    /** 阶段描述，用于日志与内部展示 */
    private final String description;

    /**
     * 根据阶段代码获取枚举实例。
     *
     * @param code 阶段代码
     * @return 对应的枚举实例，不存在则返回 null
     */
    public static InterviewStage fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (InterviewStage stage : values()) {
            if (stage.getCode().equals(code)) {
                return stage;
            }
        }
        return null;
    }
}
