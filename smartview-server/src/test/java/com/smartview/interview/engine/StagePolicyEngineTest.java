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
        engine = new StagePolicyEngine(new ObjectMapper());
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
        in.setPool(List.of(item("FOLLOW_UP", "并发")));
        // 覆盖度 BASIC.question_count 改为 5（达到 max_questions）
        in.setStageCoverageJson(COVERAGE.replace("\"question_count\": 2", "\"question_count\": 5"));

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("NEXT_STAGE");
        assertThat(d.getNextStage()).isEqualTo("PROJECT");
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
    void rule4_emptyPool_finishNoValidQuestion() {
        StagePolicyEngine.DecisionInput in = input();
        in.setScore(60);
        in.setPool(List.of());  // 追问/换题/入口全空

        StagePolicyEngine.Decision d = engine.decide(in);

        assertThat(d.getNextAction()).isEqualTo("FINISH");
        assertThat(d.getEndReason()).isEqualTo("NO_VALID_QUESTION");
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
