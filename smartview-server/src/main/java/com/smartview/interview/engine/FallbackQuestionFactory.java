package com.smartview.interview.engine;

import com.smartview.interview.model.CandidatePoolItem;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 候选池不可用时的模板化过渡题工厂。
 *
 * 存在原因（interview-policy.md 5.3 反向兜底）：
 * 候选池是"尽力而为"的缓存，Redis 缺失、快照过期、AI 服务不可用时都可能读不到候选题。
 * 旧行为是"拿不到候选 → 结束面试"（end_reason = NO_VALID_QUESTION），会把一次缓存抖动
 * 判成"候选池耗尽"，用户看到面试莫名其妙结束。本工厂把兜底方向反转为
 * "拿不到候选 → 用确定性模板题维持面试"：
 * - 零 LLM 调用、零 I/O，纯字符串渲染，因此可以在提交请求的同步链路里安全执行；
 * - 题目相关性必然弱于 AI 生成题，但保证"面试继续、阶段计划仍被遵守"；
 * - 主题由 StagePolicyEngine 依据阶段计划/覆盖度选定，本工厂只负责措辞，
 *   避免把"选什么主题"这类决策逻辑分裂到两个类里。
 *
 * 为什么每个阶段准备多个措辞变体：兜底题可能连续出好几道（例如必覆盖主题已覆盖、
 * 题量还没到 min_questions，而池一直是空的）。只准备一套话术会让用户连续看到字面
 * 完全相同的题，像页面卡死；变体按下标轮转（下标由调用方用本题提交后的阶段题量推导），
 * 因此"连续两道兜底题不会完全相同"是确定性行为，且不引入任何新状态。
 *
 * @author SmartView Team
 * @since 2026-09-14
 */
@Component
public class FallbackQuestionFactory {

    /** 来源类型：模板题不来自简历或经历语料，统一标注知识库，保证溯源字段可枚举 */
    private static final String SOURCE_TYPE = "KNOWLEDGE_BASE";

    /** 决策原因（写入候选项 reason 供溯源；真正的决策原因另由 Decision.decisionReason 记录） */
    public static final String FALLBACK_REASON = "候选池不可用，使用模板化过渡题维持面试";

    /** 同阶段换题模板：按阶段给出多种提问角度；键为阶段名 */
    private static final Map<String, List<String>> SWITCH_TEMPLATES = Map.of(
            "BASIC", List.of(
                    "我们换一个基础主题：请说明你对「%s」的理解，并结合你熟悉的项目举个具体例子。",
                    "换个基础方向：你在「%s」上踩过哪些坑？请描述当时的现象、定位过程和最终结论。",
                    "再聊一个基础点：「%s」在你实际工作中通常怎么用，有哪些边界条件需要特别注意？"),
            "PROJECT", List.of(
                    "换个方向聊项目：请围绕「%s」，介绍你实际参与过的相关工作、你的职责以及关键取舍。",
                    "继续聊项目：在与「%s」相关的部分，你做过哪些技术决策？如果重来一次会怎么改？",
                    "换个项目角度：请挑一段与「%s」有关的经历，说明你遇到的困难、你的做法和可衡量的结果。"),
            "SCENARIO", List.of(
                    "我们做一个场景题：如果线上出现与「%s」相关的问题，你会如何定位、处理并验证结果？",
                    "场景接着来：假设与「%s」相关的模块要支撑十倍流量，你会怎么设计并逐步演进？",
                    "再给一个场景：如果与「%s」相关的方案要在两周内上线且风险明确，你会如何取舍？"));

    /** 下一阶段入口模板：进入新阶段的第一问，只需一种开场措辞（每阶段只会命中一次） */
    private static final Map<String, String> ENTRY_TEMPLATES = Map.of(
            "BASIC", "我们从基础开始：请用两三句话说明「%s」的核心概念，以及它在实践中通常解决什么问题。",
            "PROJECT", "接下来聊项目：请挑一个你深度参与的项目，围绕「%s」说明你的方案、你负责的部分和最终结果。",
            "SCENARIO", "接下来做场景设计：请针对「%s」给出一个设计方案，并说明关键取舍与可能的风险。");

    /** 阶段默认主题名：阶段计划里取不到主题时使用，避免题面与覆盖度字段出现空主题 */
    private static final Map<String, String> DEFAULT_TOPICS = Map.of(
            "BASIC", "基础知识",
            "PROJECT", "项目经历",
            "SCENARIO", "场景设计");

    /** 兜底模板：阶段名不在已知取值内时使用，保证任何输入都能产出题面 */
    private static final String SWITCH_TEMPLATE_FALLBACK =
            "请围绕「%s」补充说明你的理解，并给出一个具体例子。";

    private static final String ENTRY_TEMPLATE_FALLBACK =
            "请围绕「%s」说明你的思路与做法，并给出一个具体例子。";

    /**
     * 生成同阶段换题的模板化过渡题。
     *
     * @param stage   阶段（BASIC / PROJECT / SCENARIO）
     * @param topic   目标主题；为空时取阶段默认主题
     * @param variant 措辞变体下标（调用方传本题提交后的阶段题量即可）：同一状态重复调用
     *                会得到不同措辞，避免连续两道兜底题字面完全相同
     * @return 可直接用于决策与落库的候选题（candidateType = SAME_STAGE_SWITCH）
     */
    public CandidatePoolItem createSwitchFallback(String stage, String topic, int variant) {
        String resolvedTopic = resolveTopic(stage, topic);
        List<String> templates = SWITCH_TEMPLATES.get(stage);
        String template = templates == null || templates.isEmpty()
                ? SWITCH_TEMPLATE_FALLBACK
                : templates.get(Math.floorMod(variant, templates.size()));
        return CandidatePoolItem.builder()
                .questionText(String.format(template, resolvedTopic))
                .topic(resolvedTopic)
                .stage(stage)
                .candidateType(CandidatePoolItem.TYPE_SAME_STAGE_SWITCH)
                .sourceType(SOURCE_TYPE)
                .reason(FALLBACK_REASON)
                .build();
    }

    /**
     * 生成下一阶段入口的模板化过渡题。
     *
     * @param stage 目标阶段（即将进入的阶段）
     * @param topic 目标主题；为空时取阶段默认主题
     * @return 可直接用于决策与落库的候选题（candidateType = NEXT_STAGE_ENTRY）
     */
    public CandidatePoolItem createEntryFallback(String stage, String topic) {
        String resolvedTopic = resolveTopic(stage, topic);
        return CandidatePoolItem.builder()
                .questionText(String.format(
                        ENTRY_TEMPLATES.getOrDefault(stage, ENTRY_TEMPLATE_FALLBACK), resolvedTopic))
                .topic(resolvedTopic)
                .stage(stage)
                .candidateType(CandidatePoolItem.TYPE_NEXT_STAGE_ENTRY)
                .sourceType(SOURCE_TYPE)
                .reason(FALLBACK_REASON)
                .build();
    }

    /**
     * 取阶段默认主题名（阶段计划里没有可用主题时使用）。
     */
    public String defaultTopic(String stage) {
        return DEFAULT_TOPICS.getOrDefault(stage, "当前主题");
    }

    private String resolveTopic(String stage, String topic) {
        return topic == null || topic.isBlank() ? defaultTopic(stage) : topic;
    }

    // ==================== 关于 expectedPoints ====================
    // 兜底题刻意不产出"期望回答要点"（expectedPoints 保持空列表）：
    // 模板题的考察维度是通用的，任何用主题名拼出来的要点（如"X 的核心概念与适用场景"）
    // 都会随题目一起回传给评估链路，容易被当成标准答案参与打分，反而误导评分。
    // 空列表让评估只依据题面与回答本身，符合"兜底题不承担精确评分职责"的定位。
}
