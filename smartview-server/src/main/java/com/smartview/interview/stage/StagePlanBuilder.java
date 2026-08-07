package com.smartview.interview.stage;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.enums.RoleDirection;
import com.smartview.common.exception.BusinessException;
import com.smartview.profile.entity.ProfileAnalysis;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 阶段计划生成器（Spring 端确定性生成）。
 *
 * 功能说明：
 * - 根据画像分析的 stage_targets / suggested_topics / project_graph 等材料，
 *   用确定性规则生成三阶段（BASIC/PROJECT/SCENARIO）阶段计划，写入
 *   interview_session.stage_plan_json
 * - 字段结构与 docs/interview-policy.md 2.2 保持一致（snake_case）
 *
 * 设计取舍（与 docs/interview-policy.md 1.x 职责边界对齐）：
 * - 阶段计划由 Spring 基于已落库的画像分析确定性生成，不走 FastAPI/LLM 草案，
 *   保证创建会话不依赖额外一次 LLM 调用、且计划可被 StagePolicyEngine 稳定复算；
 * - 画像分析中的 stageTargets 本身就是 LLM 按 BASIC/PROJECT/SCENARIO 产出的
 *   阶段覆盖重点，阶段计划在此之上叠加确定性题量/深度边界，避免"纯靠大模型
 *   瞎猜计划"；
 * - 每个阶段都保证 required_topics 非空（逐级回退：阶段目标 → 建议主题 →
 *   方向默认主题），确保面试不会一直锚定第一个问题，至少能覆盖三个阶段。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Component
public class StagePlanBuilder {

    private final ObjectMapper objectMapper;

    public StagePlanBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成阶段计划。
     *
     * @param analysis       画像分析结果（含 stageTargets、suggestedTopics、projectGraph）
     * @param roleDirection  面试方向，用于缺省主题回退
     * @return 结构化阶段计划
     */
    public StagePlan build(ProfileAnalysis analysis, String roleDirection) {
        List<String> basicTargets = parseStageTarget(analysis.getStageTargetsJson(), "basic");
        List<String> projectTargets = parseStageTarget(analysis.getStageTargetsJson(), "project");
        List<String> scenarioTargets = parseStageTarget(analysis.getStageTargetsJson(), "scenario");
        List<String> suggested = parseStringArray(analysis.getSuggestedTopicsJson());
        List<String> projectNames = parseProjectNames(analysis.getProjectGraphJson());

        StagePlan plan = new StagePlan();
        plan.setTotalMinQuestions(7);
        plan.setTotalMaxQuestions(20);

        // 基础八股：优先阶段目标，其次建议主题，最后方向默认主题。
        plan.addStage(buildStage(InterviewStageConst.BASIC, basicTargets, suggested, roleDirection,
                3, 8, 2));
        // 项目追问：简历项目名优先（最多 3 个），再补阶段目标中未重复的项目重点。
        List<String> projectTopics = new ArrayList<>(projectNames.subList(0, Math.min(3, projectNames.size())));
        for (String topic : projectTargets) {
            if (projectTopics.size() >= 5) {
                break;
            }
            if (!projectTopics.contains(topic)) {
                projectTopics.add(topic);
            }
        }
        if (projectTopics.isEmpty()) {
            projectTopics = defaultTopics(roleDirection, InterviewStageConst.PROJECT);
        }
        plan.addStage(buildStage(InterviewStageConst.PROJECT, projectTopics, suggested, roleDirection,
                2, 6, 3));
        // 场景设计：优先阶段目标，其次建议主题。
        plan.addStage(buildStage(InterviewStageConst.SCENARIO, scenarioTargets, suggested, roleDirection,
                2, 6, 2));

        return plan;
    }

    /**
     * 组装单个阶段；required_topics 为空时按 阶段目标 → 建议主题 → 方向默认 回退。
     */
    private StagePlanItem buildStage(
            String stage, List<String> targets, List<String> suggested,
            String roleDirection, int minQuestions, int maxQuestions, int maxFollowUpDepth) {
        List<String> topics = new ArrayList<>(targets);
        if (topics.isEmpty()) {
            topics = new ArrayList<>(suggested);
        }
        if (topics.isEmpty()) {
            topics = defaultTopics(roleDirection, stage);
        }
        // 去重并限制阶段必覆盖主题数量，避免阶段计划被海量主题撑爆。
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(topics));
        if (distinct.size() > 5) {
            distinct = distinct.subList(0, 5);
        }
        return StagePlanItem.builder()
                .stage(stage)
                .minQuestions(minQuestions)
                .maxQuestions(maxQuestions)
                .requiredTopics(distinct)
                .maxFollowUpDepth(maxFollowUpDepth)
                .switchConditions("当前阶段题量达到 max_questions 时强制进入下一阶段；"
                        + "必覆盖主题全部覆盖且题量达到 min_questions 时可进入下一阶段")
                .build();
    }

    /**
     * 方向默认主题：画像分析材料缺失时的兜底，保证阶段计划非空且可推进。
     */
    private List<String> defaultTopics(String roleDirection, String stage) {
        boolean java = RoleDirection.JAVA_BACKEND.getCode().equals(roleDirection);
        if (InterviewStageConst.BASIC.equals(stage)) {
            return java
                    ? List.of("Java 并发", "JVM", "Spring 框架")
                    : List.of("LangGraph 状态机", "RAG 检索增强", "Agent 工具调用");
        }
        if (InterviewStageConst.PROJECT.equals(stage)) {
            return java
                    ? List.of("简历项目技术选型", "系统架构设计")
                    : List.of("Agent 工作流设计", "知识库构建");
        }
        return java
                ? List.of("分布式系统设计", "性能优化")
                : List.of("多智能体编排", "Agent 可靠性设计");
    }

    /**
     * 解析普通字符串数组 JSON（suggestedTopics）。
     */
    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
            return result;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * 解析阶段目标 JSON 中指定字段的字符串数组（stageTargets.basic/project/scenario）。
     */
    private List<String> parseStageTarget(String json, String field) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode array = node.get(field);
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            array.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
            return result;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * 解析项目图谱 JSON 中的项目名称（projectGraph.projects[].projectName）。
     */
    private List<String> parseProjectNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode projects = node.get("projects");
            if (projects == null || !projects.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            projects.forEach(project -> {
                JsonNode name = project.get("projectName");
                if (name != null && name.isTextual() && !name.asText().isBlank()) {
                    result.add(name.asText());
                }
            });
            return result;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    /**
     * 阶段代码常量，避免与枚举重复引入的样板；仅用于本生成器内部回退判断。
     */
    private static final class InterviewStageConst {
        private static final String BASIC = "BASIC";
        private static final String PROJECT = "PROJECT";
        private static final String SCENARIO = "SCENARIO";
    }

    /**
     * 结构化阶段计划（snake_case 序列化，与 docs/interview-policy.md 2.2 对齐）。
     */
    @Getter
    @Setter
    public static class StagePlan {

        /** 面试策略版本，创建会话后保持不变 */
        @JsonProperty("policy_version")
        private String policyVersion = "1.0";

        /** 全场最少题数 */
        @JsonProperty("total_min_questions")
        private Integer totalMinQuestions;

        /** 全场题量硬上限 */
        @JsonProperty("total_max_questions")
        private Integer totalMaxQuestions;

        /** 阶段列表 */
        private List<StagePlanItem> stages = new ArrayList<>();

        private void addStage(StagePlanItem stage) {
            stages.add(stage);
        }

        /**
         * 序列化为落库 JSON。
         *
         * @param objectMapper 序列化器
         * @return stage_plan_json 字符串
         */
        public String toJson(ObjectMapper objectMapper) {
            try {
                return objectMapper.writeValueAsString(this);
            } catch (JsonProcessingException exception) {
                throw new BusinessException("阶段计划序列化失败");
            }
        }
    }

    /**
     * 单个阶段计划项。
     */
    @Getter
    @Setter
    public static class StagePlanItem {

        private String stage;

        /** 本阶段最少题数 */
        @JsonProperty("min_questions")
        private Integer minQuestions;

        /** 本阶段最多题数 */
        @JsonProperty("max_questions")
        private Integer maxQuestions;

        /** 本阶段必覆盖主题 */
        @JsonProperty("required_topics")
        private List<String> requiredTopics;

        /** 单主题最大连续追问深度 */
        @JsonProperty("max_follow_up_depth")
        private Integer maxFollowUpDepth;

        /** 切换主题或进入下一阶段的条件 */
        @JsonProperty("switch_conditions")
        private String switchConditions;

        private StagePlanItem() {
        }

        private static StagePlanItemBuilder builder() {
            return new StagePlanItemBuilder();
        }

        private static final class StagePlanItemBuilder {
            private final StagePlanItem item = new StagePlanItem();

            private StagePlanItemBuilder stage(String stage) {
                item.stage = stage;
                return this;
            }

            private StagePlanItemBuilder minQuestions(int min) {
                item.minQuestions = min;
                return this;
            }

            private StagePlanItemBuilder maxQuestions(int max) {
                item.maxQuestions = max;
                return this;
            }

            private StagePlanItemBuilder requiredTopics(List<String> topics) {
                item.requiredTopics = topics;
                return this;
            }

            private StagePlanItemBuilder maxFollowUpDepth(int depth) {
                item.maxFollowUpDepth = depth;
                return this;
            }

            private StagePlanItemBuilder switchConditions(String conditions) {
                item.switchConditions = conditions;
                return this;
            }

            private StagePlanItem build() {
                return item;
            }
        }
    }
}
