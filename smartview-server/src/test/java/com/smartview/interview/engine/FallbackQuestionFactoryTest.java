package com.smartview.interview.engine;

import com.smartview.interview.model.CandidatePoolItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模板化兜底题工厂测试。
 *
 * 覆盖验收标准（interview-policy.md 5.3）：
 * 1. 任一阶段都能产出非空题面，保证空池时"面试不会停"；
 * 2. candidateType 与决策动作对应（换题 / 阶段入口），事务层据此映射 question_type；
 * 3. 主题缺失时用阶段默认主题兜底，不产生空主题（否则覆盖度字段会写入 null）；
 * 4. 措辞变体按下标轮转：连续两道兜底题不能字面完全相同（用户侧表现为"页面卡死重复出题"）；
 * 5. 刻意不产出 expectedPoints：模板题不承担精确评分职责，避免拼凑的"要点"误导评估。
 */
class FallbackQuestionFactoryTest {

    private final FallbackQuestionFactory factory = new FallbackQuestionFactory();

    @Test
    void createSwitchFallback_每个阶段都产出题面但不要点() {
        for (String stage : new String[]{"BASIC", "PROJECT", "SCENARIO"}) {
            CandidatePoolItem item = factory.createSwitchFallback(stage, "JVM", 0);

            assertThat(item.getQuestionText()).isNotBlank().contains("JVM");
            assertThat(item.getTopic()).isEqualTo("JVM");
            assertThat(item.getStage()).isEqualTo(stage);
            assertThat(item.getCandidateType()).isEqualTo(CandidatePoolItem.TYPE_SAME_STAGE_SWITCH);
            assertThat(item.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
            // 不编造期望答题要点，避免模板要点被评估当成标准答案参与打分
            assertThat(item.getExpectedPoints()).isNullOrEmpty();
            assertThat(item.getReason()).isEqualTo(FallbackQuestionFactory.FALLBACK_REASON);
        }
    }

    @Test
    void createEntryFallback_类型为下一阶段入口() {
        CandidatePoolItem item = factory.createEntryFallback("PROJECT", "电商");

        assertThat(item.getCandidateType()).isEqualTo(CandidatePoolItem.TYPE_NEXT_STAGE_ENTRY);
        assertThat(item.getQuestionText()).contains("电商");
        assertThat(item.getStage()).isEqualTo("PROJECT");
    }

    @Test
    void 主题缺失时回退到阶段默认主题() {
        assertThat(factory.createSwitchFallback("BASIC", null, 0).getTopic()).isEqualTo("基础知识");
        assertThat(factory.createSwitchFallback("PROJECT", "  ", 0).getTopic()).isEqualTo("项目经历");
        assertThat(factory.createEntryFallback("SCENARIO", null).getTopic()).isEqualTo("场景设计");
    }

    @Test
    void 未知阶段仍能产出题面_不抛异常() {
        // 阶段名不在契约取值内也必须可用：兜底链路的目标是"绝不因缺题而中断面试"
        CandidatePoolItem item = factory.createSwitchFallback("UNKNOWN", null, 0);

        assertThat(item.getQuestionText()).isNotBlank();
        assertThat(item.getTopic()).isEqualTo("当前主题");
        assertThat(item.getStage()).isEqualTo("UNKNOWN");
    }

    @Test
    void 措辞变体轮转_连续两道兜底题不相同() {
        CandidatePoolItem first = factory.createSwitchFallback("BASIC", "JVM", 3);
        CandidatePoolItem second = factory.createSwitchFallback("BASIC", "JVM", 4);

        assertThat(second.getQuestionText()).isNotEqualTo(first.getQuestionText());
        // 下标超过变体数量时循环取用，不抛越界异常
        assertThat(factory.createSwitchFallback("BASIC", "JVM", 9).getQuestionText())
                .isEqualTo(factory.createSwitchFallback("BASIC", "JVM", 0).getQuestionText());
        assertThat(factory.createSwitchFallback("UNKNOWN", "JVM", 5).getQuestionText()).isNotBlank();
    }
}
