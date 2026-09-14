package com.smartview.interview.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.interview.model.CandidatePoolItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StagePolicyEngine 确定性决策测试（docs/interview-policy.md 2.4 规则 1-5）。
 */
class StagePolicyEngineTest {

    private static final String PLAN = """
            {
              "policy_version": "1.0",
              "total_min_questions": 7,
              "total_max_questions": 20,
              "stages": [
                {"stage": "BASIC", "min_questions": 3, "max_questions": 5,
                 "required_topics": ["并发", "JVM", "Spring"], "max_follow_up_depth": 2},
                {"stage": "PROJECT", "min_questions": 2, "max_questions": 5,
                 "required_topics": ["电商"], "max_follow_up_depth": 2}
              ]
            }
            """;

    private static final String COVERAGE = """
            {
              "BASIC": {"question_count": 2, "covered_topics": ["并发"],
                        "missing_topics": ["JVM", "Spring"], "current_topic_follow_up_count": 0},
              "PROJECT": {"question_count": 0, "covered_topics": [],
                          "missing_topics": ["电商"], "current_topic_follow_up_count": 0}
            }
            """;

    private StagePolicyEngine engine;

    @BeforeEach
    void setUp() {
        engine = new StagePolicyEngine(new ObjectMapper(), new FallbackQuestionFactory());
    }

    private StagePolicyEngine.DecisionInput input() {
        StagePolicyEngine.DecisionInput in = new StagePolicyEngine.DecisionInput();
        in.setStagePlanJson(PLAN);
        in.setStageCoverageJson(COVERAGE);
        in.setCurrentStage("BASIC");
        in.setCurrentTopic("并发");
        in.setQuestionCount(2);
        in.setScore(75);
        in.setConsecutiveWeakCount(0);
        return in;
    }

    private CandidatePoolItem item(String type, String topic) {
        return CandidatePoolItem.builder().questionText("关于" + topic).topic(topic)
                .stage("BASIC").candidateType(type).build();
    }

    @Test
    void rule1_totalMax_reachesFinish() {
        StagePolicyEngine.DecisionInput in = input();
        in.setQuestionCount(20);
        in.setPool(List.of(item("FOLLOW_UP", "并发")));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("FINISH");
        assertThat(d.getEndReason()).isEqualTo("QUESTION_LIMIT");
    }

    @Test
    void rule1_consecutiveWeak_finishesWithQualityTooLow() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(20);
        in.setConsecutiveWeakCount(3);
        in.setPool(List.of(item("SAME_STAGE_SWITCH", "JVM")));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("FINISH");
        assertThat(d.getEndReason()).isEqualTo("QUALITY_TOO_LOW");
    }

    @Test
    void rule2_stageMax_forcesNextStage() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(60);
        in.setPool(List.of(item("FOLLOW_UP", "并发"), item("NEXT_STAGE_ENTRY", "电商")));
        // 覆盖度 BASIC.question_count 改为 5（达到 max_questions）
        in.setStageCoverageJson(COVERAGE.replace("\"question_count\": 2", "\"question_count\": 5"));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("NEXT_STAGE");
        assertThat(d.getNextStage()).isEqualTo("PROJECT");
        // 不变量：非 FINISH 决策必带候选
        assertThat(d.getSelectedCandidate()).isNotNull();
    }

    @Test
    void rule2_stageMax_withoutEntryCandidate_usesTemplateEntry() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(60);
        in.setPool(List.of(item("FOLLOW_UP", "并发")));  // 无 NEXT_STAGE_ENTRY 候选
        in.setStageCoverageJson(COVERAGE.replace("\"question_count\": 2", "\"question_count\": 5"));

        StagePolicyEngine.Decision d = engine.decide(in);

        // 必须推进但池里没有入口候选：改用模板化入口题推进，而不是把面试判成"无题可问"
        assertThat(d.getNextAction()).isEqualTo("NEXT_STAGE");
        assertThat(d.getNextStage()).isEqualTo("PROJECT");
        assertThat(d.getSelectedCandidate().getCandidateType()).isEqualTo("NEXT_STAGE_ENTRY");
        assertThat(d.getSelectedCandidate().getStage()).isEqualTo("PROJECT");
        // 主题取下一阶段的首个必覆盖主题（PLAN 中 PROJECT 配置为「电商」）
        assertThat(d.getSelectedCandidate().getTopic()).isEqualTo("电商");
        assertThat(d.getSelectedCandidate().getQuestionText()).isNotBlank();
    }

    @Test
    void rule3_depthLimited_forbidsFollowUp() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(80);
        in.setPool(List.of(item("FOLLOW_UP", "并发"), item("SAME_STAGE_SWITCH", "JVM")));
        // 覆盖度 current_topic_follow_up_count=2 达到 max_follow_up_depth=2
        in.setStageCoverageJson(COVERAGE.replace("\"current_topic_follow_up_count\": 0",
                "\"current_topic_follow_up_count\": 2"));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("SWITCH_TOPIC");
        assertThat(d.getSelectedCandidate().getCandidateType()).isEqualTo("SAME_STAGE_SWITCH");
    }

    @Test
    void rule5_highScoreWithFollowUp_followUp() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(80);
        in.setPool(List.of(item("FOLLOW_UP", "并发"), item("SAME_STAGE_SWITCH", "JVM")));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("FOLLOW_UP");
        assertThat(d.getSelectedCandidate().getCandidateType()).isEqualTo("FOLLOW_UP");
    }

    @Test
    void rule5_midScore_switchTopic() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(55);
        in.setPool(List.of(item("SAME_STAGE_SWITCH", "JVM")));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("SWITCH_TOPIC");
        assertThat(d.getSelectedCandidate().getTopic()).isEqualTo("JVM");
    }

    @Test
    void rule5_lowScore_switchTopic() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(20);
        in.setPool(List.of(item("SAME_STAGE_SWITCH", "JVM")));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("SWITCH_TOPIC");
    }

    @Test
    void rule4_emptyPool_usesTemplateSwitchOnMissingTopic() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(60);
        in.setPool(List.of());  // 追问/换题/入口全空

        StagePolicyEngine.Decision d = engine.decide(in);

        // 反转兜底方向：空池不再结束面试，优先补齐当前阶段未覆盖的必覆盖主题
        // （BASIC 已覆盖「并发」，required_topics 中「JVM」尚未覆盖）
        assertThat(d.getNextAction()).isEqualTo("SWITCH_TOPIC");
        assertThat(d.getSelectedCandidate().getCandidateType()).isEqualTo("SAME_STAGE_SWITCH");
        assertThat(d.getSelectedCandidate().getTopic()).isEqualTo("JVM");
        assertThat(d.getSelectedCandidate().getQuestionText()).contains("JVM");
        assertThat(d.getEndReason()).isNull();
    }

    @Test
    void 空池兜底不变量_任何降级路径都不产出NO_VALID_QUESTION() {
        // 覆盖三种原本会 FINISH(NO_VALID_QUESTION) 的路径：空池、阶段达上限且无入口、最后一个阶段无下一阶段
        StagePolicyEngine.DecisionInput basicEmpty = input();
        basicEmpty.setScore(60);
        basicEmpty.setPool(List.of());

        StagePolicyEngine.DecisionInput basicMaxed = input();
        basicMaxed.setScore(60);
        basicMaxed.setPool(List.of());
        basicMaxed.setStageCoverageJson(
                COVERAGE.replace("\"question_count\": 2", "\"question_count\": 5"));

        StagePolicyEngine.DecisionInput lastStage = input();
        lastStage.setScore(60);
        lastStage.setPool(List.of());
        lastStage.setCurrentStage("SCENARIO");
        lastStage.setCurrentTopic("场景设计");
        lastStage.setQuestionCount(5);
        lastStage.setStageCoverageJson("""
                {
                  "BASIC": {"question_count": 5, "covered_topics": ["并发", "JVM", "Spring"],
                            "missing_topics": [], "current_topic_follow_up_count": 0},
                  "SCENARIO": {"question_count": 1, "covered_topics": ["场景设计"],
                               "missing_topics": [], "current_topic_follow_up_count": 0}
                }
                """);

        for (StagePolicyEngine.DecisionInput in : List.of(basicEmpty, basicMaxed, lastStage)) {
            StagePolicyEngine.Decision d = engine.decide(in);

            assertThat(d.getEndReason()).isNotEqualTo("NO_VALID_QUESTION");
            if (!"FINISH".equals(d.getNextAction())) {
                assertThat(d.getSelectedCandidate())
                        .as("非 FINISH 决策必须携带候选题").isNotNull();
                assertThat(d.getSelectedCandidate().getQuestionText()).isNotBlank();
            }
        }
    }

    @Test
    void 当前阶段已满足推进条件且池为空_改用模板入口题推进() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(60);
        in.setPool(List.of());
        in.setCurrentStage("PROJECT");
        in.setCurrentTopic("电商");
        in.setQuestionCount(3);
        in.setStageCoverageJson("""
                {
                  "BASIC": {"question_count": 5, "covered_topics": ["并发", "JVM", "Spring"],
                            "missing_topics": [], "current_topic_follow_up_count": 0},
                  "PROJECT": {"question_count": 1, "covered_topics": ["电商"],
                              "missing_topics": [], "current_topic_follow_up_count": 0}
                }
                """);

        StagePolicyEngine.Decision d = engine.decide(in);

        // PROJECT 覆盖齐备且达到 min_questions(2) → 模板入口题推进；
        // PLAN 未配置 SCENARIO 阶段，主题回退到阶段默认名而不是 null
        assertThat(d.getNextAction()).isEqualTo("NEXT_STAGE");
        assertThat(d.getNextStage()).isEqualTo("SCENARIO");
        assertThat(d.getSelectedCandidate().getCandidateType()).isEqualTo("NEXT_STAGE_ENTRY");
        assertThat(d.getSelectedCandidate().getTopic()).isEqualTo("场景设计");
    }

    @Test
    void rule2_allStagesSatisfied_finishPlanCompleted() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(60);
        in.setPool(List.of());
        // BASIC 全部覆盖且 ≥min；PROJECT 也满足（count≥max 视为满足）
        String coverage = COVERAGE
                .replace("\"question_count\": 2", "\"question_count\": 3")
                .replace("\"covered_topics\": [\"并发\"]", "\"covered_topics\": [\"并发\", \"JVM\", \"Spring\"]")
                .replace("\"missing_topics\": [\"JVM\", \"Spring\"]", "\"missing_topics\": []")
                .replace("\"PROJECT\": {\"question_count\": 0", "\"PROJECT\": {\"question_count\": 5");
        in.setStageCoverageJson(coverage);
        in.setQuestionCount(8);

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("FINISH");
        assertThat(d.getEndReason()).isEqualTo("PLAN_COMPLETED");
    }

    @Test
    void 计划完成判定与候选池无关_池非空时同样按计划完成结束() {
        // 反例守卫：规则2 的"计划完成"只看必覆盖主题与各阶段最小题量，与候选池可用性无关。
        // 池里还有候选也不该继续出题——否则"面试问多久"会依赖缓存是否命中，无法复现。
        // 同一份覆盖度分别配"空池"与"候选充足"，结束原因必须一致。
        String coverage = COVERAGE
                .replace("\"question_count\": 2", "\"question_count\": 3")
                .replace("\"covered_topics\": [\"并发\"]", "\"covered_topics\": [\"并发\", \"JVM\", \"Spring\"]")
                .replace("\"missing_topics\": [\"JVM\", \"Spring\"]", "\"missing_topics\": []")
                .replace("\"PROJECT\": {\"question_count\": 0", "\"PROJECT\": {\"question_count\": 5");

        StagePolicyEngine.DecisionInput emptyPool = input();
        emptyPool.setScore(60);
        emptyPool.setPool(List.of());
        emptyPool.setStageCoverageJson(coverage);
        emptyPool.setQuestionCount(8);

        StagePolicyEngine.DecisionInput richPool = input();
        richPool.setScore(60);
        richPool.setPool(List.of(
                item("FOLLOW_UP", "并发"),
                item("SAME_STAGE_SWITCH", "JVM"),
                item("NEXT_STAGE_ENTRY", "电商")));
        richPool.setStageCoverageJson(coverage);
        richPool.setQuestionCount(8);

        StagePolicyEngine.Decision emptyDecision = engine.decide(emptyPool);
        StagePolicyEngine.Decision richDecision = engine.decide(richPool);

        assertThat(emptyDecision.getNextAction()).isEqualTo("FINISH");
        assertThat(richDecision.getNextAction()).isEqualTo("FINISH");
        assertThat(emptyDecision.getEndReason()).isEqualTo("PLAN_COMPLETED");
        assertThat(richDecision.getEndReason()).isEqualTo("PLAN_COMPLETED");
    }

    @Test
    void 连续空池兜底_同一状态不会连续产出同一道题() {
        // BASIC 必覆盖主题已全部覆盖、题量 3（min=3 时下一题即可推进），
        // 这里把 max 抬到 5 且 min 提到 4，构造"覆盖齐但题量没到 min、池又空"的连续兜底窗口
        String plan = PLAN.replace("\"min_questions\": 3, \"max_questions\": 5",
                "\"min_questions\": 5, \"max_questions\": 6");
        String coverage = COVERAGE
                .replace("\"covered_topics\": [\"并发\"]", "\"covered_topics\": [\"并发\", \"JVM\", \"Spring\"]")
                .replace("\"missing_topics\": [\"JVM\", \"Spring\"]", "\"missing_topics\": []");

        StagePolicyEngine.DecisionInput first = input();
        first.setStagePlanJson(plan);
        first.setStageCoverageJson(coverage);
        first.setScore(60);
        first.setPool(List.of());
        first.setQuestionCount(3);

        StagePolicyEngine.DecisionInput second = input();
        second.setStagePlanJson(plan);
        // 阶段题量 +1（决策用的是"本题提交后"的有效题量，因此必须改覆盖度而不是总题量）
        second.setStageCoverageJson(coverage.replace("\"question_count\": 2", "\"question_count\": 3"));
        second.setScore(60);
        second.setPool(List.of());
        second.setQuestionCount(4);

        StagePolicyEngine.Decision firstDecision = engine.decide(first);
        StagePolicyEngine.Decision secondDecision = engine.decide(second);

        assertThat(firstDecision.getNextAction()).isEqualTo("SWITCH_TOPIC");
        assertThat(secondDecision.getNextAction()).isEqualTo("SWITCH_TOPIC");
        assertThat(firstDecision.getSelectedCandidate().getQuestionText())
                .isNotEqualTo(secondDecision.getSelectedCandidate().getQuestionText());
        // 主题在必覆盖主题间轮转，避免反复问同一主题
        assertThat(firstDecision.getSelectedCandidate().getTopic())
                .isNotEqualTo(secondDecision.getSelectedCandidate().getTopic());
    }

    @Test
    void 阶段计划缺失时_总题量上限仍然生效() {
        // stage_plan_json 解析失败或字段缺失时不能退回 Integer.MAX_VALUE：
        // 否则规则1 永不触发，配合空池模板题会让面试无限出题
        StagePolicyEngine.DecisionInput in = input();
        in.setStagePlanJson("");   // 空计划：parse 后为空对象，取不到 total_max_questions
        in.setScore(60);
        in.setPool(List.of());
        in.setQuestionCount(20);   // 达到默认总题量上限

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("FINISH");
        assertThat(d.getEndReason()).isEqualTo("QUESTION_LIMIT");
        assertThat(d.getDecisionReason()).contains("20");
    }

    @Test
    void stageMax_effectiveCount_advancesWhenCurrentAnswerReachesMax() {
        // BASIC 已答 4 题（max=5），本题为第 5 题：落库后题量=5 → 应推进（修复 off-by-one）
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(70);
        in.setPool(List.of(item("NEXT_STAGE_ENTRY", "电商")));
        in.setAnsweredQuestionType("OPENING");
        in.setAnsweredTopic("并发");
        in.setStageCoverageJson(COVERAGE.replace("\"question_count\": 2", "\"question_count\": 4"));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("NEXT_STAGE");
        assertThat(d.getNextStage()).isEqualTo("PROJECT");
    }

    @Test
    void mediumScore_withOnlyFollowUps_followUpKeepsInterviewAlive() {
        // 得分 60、池中仅有追问候选（无换题/入口）：无法换题或推进，兜底追问保持面试
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(60);
        in.setPool(List.of(item("FOLLOW_UP", "并发")));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("FOLLOW_UP");
        assertThat(d.getSelectedCandidate()).isNotNull();
    }

    @Test
    void switchCandidatePrefersMissingTopic() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(55);
        in.setPool(List.of(
                item("SAME_STAGE_SWITCH", "并发"),   // 已覆盖主题，应被跳过
                item("SAME_STAGE_SWITCH", "JVM")));  // 缺失主题，应优先选中

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("SWITCH_TOPIC");
        assertThat(d.getSelectedCandidate().getTopic()).isEqualTo("JVM");
    }
}
