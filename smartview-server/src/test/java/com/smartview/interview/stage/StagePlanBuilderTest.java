package com.smartview.interview.stage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.enums.RoleDirection;
import com.smartview.profile.entity.ProfileAnalysis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段计划生成器测试。
 *
 * 覆盖验收标准：
 * - 阶段计划至少覆盖 BASIC / PROJECT / SCENARIO 三个阶段（不会一直锚定第一个主题）
 * - 每阶段含最小/最大题量、必覆盖主题、单主题最大追问深度
 * - 含总题量上限与切阶段条件
 * - 必覆盖主题从画像分析提取，且逐级回退（阶段目标 → 建议主题 → 方向默认）
 */
class StagePlanBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StagePlanBuilder builder = new StagePlanBuilder(objectMapper);

    private ProfileAnalysis analysis(
            String stageTargets, String suggestedTopics, String projectGraph) {
        return ProfileAnalysis.builder()
                .id(1L)
                .userId(7L)
                .resumeProfileId(10L)
                .roleDirection(RoleDirection.JAVA_BACKEND.getCode())
                .profileVersion(1)
                .stageTargetsJson(stageTargets)
                .suggestedTopicsJson(suggestedTopics)
                .projectGraphJson(projectGraph)
                .build();
    }

    @Test
    void build_覆盖三阶段且包含题量与深度边界() {
        StagePlanBuilder.StagePlan plan = builder.build(
                analysis("{\"basic\":[\"Java 并发\"],\"project\":[\"高并发架构\"],\"scenario\":[\"分布式一致性\"]}",
                        "[\"JVM\"]",
                        "{\"projects\":[{\"projectName\":\"电商平台\"}]}"),
                RoleDirection.JAVA_BACKEND.getCode());

        assertThat(plan.getTotalMinQuestions()).isEqualTo(7);
        assertThat(plan.getTotalMaxQuestions()).isEqualTo(20);
        assertThat(plan.getStages()).hasSize(3);
        assertThat(plan.getStages()).extracting(StagePlanBuilder.StagePlanItem::getStage)
                .containsExactly("BASIC", "PROJECT", "SCENARIO");

        StagePlanBuilder.StagePlanItem basic = plan.getStages().get(0);
        assertThat(basic.getMinQuestions()).isEqualTo(3);
        assertThat(basic.getMaxQuestions()).isEqualTo(8);
        assertThat(basic.getRequiredTopics()).contains("Java 并发");
        assertThat(basic.getMaxFollowUpDepth()).isEqualTo(2);
        assertThat(basic.getSwitchConditions()).isNotBlank();

        StagePlanBuilder.StagePlanItem project = plan.getStages().get(1);
        // PROJECT 必覆盖主题 = 简历项目名 + 阶段目标项目主题
        assertThat(project.getRequiredTopics()).contains("电商平台", "高并发架构");
        assertThat(project.getMaxFollowUpDepth()).isEqualTo(3);
    }

    @Test
    void build_序列化为snake_case且符合政策格式() throws Exception {
        StagePlanBuilder.StagePlan plan = builder.build(
                analysis("{\"basic\":[\"Java 并发\"],\"project\":[],\"scenario\":[\"系统设计\"]}",
                        "[\"JVM\",\"Spring\"]",
                        "{\"projects\":[]}"),
                RoleDirection.JAVA_BACKEND.getCode());

        JsonNode node = objectMapper.readTree(plan.toJson(objectMapper));
        assertThat(node.get("policy_version").asText()).isEqualTo("1.0");
        assertThat(node.get("total_min_questions").asInt()).isEqualTo(7);
        assertThat(node.get("total_max_questions").asInt()).isEqualTo(20);
        assertThat(node.get("stages")).hasSize(3);
        JsonNode basic = node.get("stages").get(0);
        assertThat(basic.get("stage").asText()).isEqualTo("BASIC");
        assertThat(basic.get("min_questions").asInt()).isEqualTo(3);
        assertThat(basic.get("required_topics").isArray()).isTrue();
        assertThat(basic.get("max_follow_up_depth").asInt()).isEqualTo(2);
        assertThat(basic.has("switch_conditions")).isTrue();
    }

    @Test
    void build_阶段目标缺失时回退到建议主题() {
        StagePlanBuilder.StagePlan plan = builder.build(
                analysis("{\"basic\":[],\"project\":[],\"scenario\":[]}",
                        "[\"Java 并发\",\"JVM\",\"Spring\"]",
                        "{\"projects\":[]}"),
                RoleDirection.JAVA_BACKEND.getCode());

        assertThat(plan.getStages().get(0).getRequiredTopics()).contains("Java 并发");
        assertThat(plan.getStages().get(0).getRequiredTopics()).contains("JVM");
    }

    @Test
    void build_画像材料全缺失时回退方向默认主题() {
        StagePlanBuilder.StagePlan plan = builder.build(
                analysis(null, null, null),
                RoleDirection.JAVA_BACKEND.getCode());

        // 三个阶段均非空，面试不会一直锚定第一个主题
        for (StagePlanBuilder.StagePlanItem stage : plan.getStages()) {
            assertThat(stage.getRequiredTopics()).isNotEmpty();
        }
        assertThat(plan.getStages().get(0).getRequiredTopics())
                .contains("Java 并发", "JVM", "Spring 框架");
    }

    @Test
    void build_必覆盖主题去重且限制数量() {
        StagePlanBuilder.StagePlan plan = builder.build(
                analysis("{\"basic\":[\"A\",\"A\",\"B\",\"C\",\"D\",\"E\",\"F\"],\"project\":[],\"scenario\":[]}",
                        null,
                        "{\"projects\":[]}"),
                RoleDirection.JAVA_BACKEND.getCode());

        // 去重后最多 5 个
        assertThat(plan.getStages().get(0).getRequiredTopics())
                .doesNotHaveDuplicates()
                .hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void build_Agent方向使用对应默认主题() {
        StagePlanBuilder.StagePlan plan = builder.build(
                analysis(null, null, null),
                RoleDirection.AGENT_DEVELOPMENT.getCode());

        assertThat(plan.getStages().get(0).getRequiredTopics())
                .contains("LangGraph 状态机", "RAG 检索增强");
    }
}
