package com.smartview.interview.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.interview.model.CandidatePoolItem;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 面试下一步动作确定性决策引擎（docs/interview-policy.md 2.4 规则 1-5）。
 *
 * 职责边界（interview-policy.md 1.2）：FastAPI 只返回评估事实与候选池，本引擎
 * 依据阶段计划、覆盖度、评估事实与合并候选池独立决策 nextAction，不依赖 AI 建议。
 *
 * 决策优先级：规则1 硬性终止 → 规则2 阶段推进(必须) → 规则5 正常流程
 * → 规则4 候选池为空降级 → 规则2 阶段推进(可) → 兜底。
 *
 * @author SmartView Team
 * @since 2026-08-09
 */
@Slf4j
@Component
public class StagePolicyEngine {

    /** 下一步动作常量，与 answer_evaluation.next_action 存储值一致 */
    public static final String ACTION_FOLLOW_UP = "FOLLOW_UP";
    public static final String ACTION_SWITCH_TOPIC = "SWITCH_TOPIC";
    public static final String ACTION_NEXT_STAGE = "NEXT_STAGE";
    public static final String ACTION_FINISH = "FINISH";

    /** 结束原因常量，与 interview_session.end_reason 存储值一致 */
    public static final String END_QUESTION_LIMIT = "QUESTION_LIMIT";
    public static final String END_QUALITY_TOO_LOW = "QUALITY_TOO_LOW";
    public static final String END_PLAN_COMPLETED = "PLAN_COMPLETED";
    public static final String END_NO_VALID_QUESTION = "NO_VALID_QUESTION";

    /** 候选类型常量（与 ai-api 契约 CandidatePoolItem.candidateType 一致） */
    private static final String CANDIDATE_FOLLOW_UP = "FOLLOW_UP";
    private static final String CANDIDATE_SWITCH = "SAME_STAGE_SWITCH";
    private static final String CANDIDATE_ENTRY = "NEXT_STAGE_ENTRY";

    private final ObjectMapper objectMapper;

    public StagePolicyEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 决策输入：阶段计划/覆盖度 JSON、会话指针、评估事实、候选池、连续弱答计数。
     */
    @Data
    public static class DecisionInput {
        /** 阶段计划 JSON（snake_case，见 interview-policy.md 2.2） */
        private String stagePlanJson;
        /** 阶段覆盖度 JSON（snake_case，见 interview-policy.md 2.3） */
        private String stageCoverageJson;
        /** 当前阶段：BASIC / PROJECT / SCENARIO */
        private String currentStage;
        /** 当前主题 */
        private String currentTopic;
        /** 会话累计题量（用于 total_max / total_min 判断） */
        private int questionCount;
        /** 当前回答得分 */
        private int score;
        /** 当前回答命中要点 */
        private List<String> matchedPoints = new ArrayList<>();
        /** 连续弱答计数（含当前题，得分<30 且无要点） */
        private int consecutiveWeakCount;
        /** 合并后的候选池（追问 + 同阶段换题 + 下一阶段入口） */
        private List<CandidatePoolItem> pool = new ArrayList<>();
        /** 被回答题的类型（FOLLOW_UP 与否影响当前主题追问深度计数） */
        private String answeredQuestionType;
        /** 被回答题的主题（用于覆盖判定：本题落库后该主题视为已覆盖） */
        private String answeredTopic;
    }

    /**
     * 决策输出：下一步动作、目标阶段、结束原因、选中的下一题、决策原因与未选中候选。
     */
    @Data
    public static class Decision {
        /** 下一步动作：FOLLOW_UP / SWITCH_TOPIC / NEXT_STAGE / FINISH */
        private String nextAction;
        /** NEXT_STAGE 时的目标阶段 */
        private String nextStage;
        /** FINISH 时的结束原因 */
        private String endReason;
        /** 选中的下一题候选（FINISH 时为 null） */
        private CandidatePoolItem selectedCandidate;
        /** 决策原因，用于审计与调试（interview-policy.md 9.2） */
        private String decisionReason;
        /** 未选中的候选（审计用） */
        private List<CandidatePoolItem> excluded = new ArrayList<>();
    }

    /**
     * 按 policy 2.4 优先级执行确定性决策。
     *
     * 顺序：规则1 硬性终止 → 规则2 全部阶段满足则结束 → 规则2 当前阶段达到 max 必须推进
     * → 规则3/5 正常流程 → 规则4 空池降级 → 规则2 当前阶段可推进 → 兜底。
     *
     * 不变量：非 FINISH 决策必然携带 selectedCandidate，事务层据此落库下一题；
     * 无法取得候选的推进一律降级为 FINISH(NO_VALID_QUESTION)，避免 500。
     */
    public Decision decide(DecisionInput in) {
        JsonNode plan = parse(in.getStagePlanJson());
        JsonNode coverage = parse(in.getStageCoverageJson());
        JsonNode currentPlan = planStage(plan, in.getCurrentStage());
        int totalCount = in.getQuestionCount();
        boolean currentIsFollowUp = "FOLLOW_UP".equals(in.getAnsweredQuestionType());

        // 规则1（最高优先级）：硬性终止条件
        if (totalCount >= totalMax(plan)) {
            return finish(END_QUESTION_LIMIT, "总题量达到上限 " + totalMax(plan) + "，结束面试");
        }
        if (in.getConsecutiveWeakCount() >= 3) {
            return finish(END_QUALITY_TOO_LOW, "连续 3 题评估失败（得分<30 且无有效要点），提前结束");
        }

        // 候选池分类（policy 3.4 排序依据）
        List<CandidatePoolItem> followUps = candidatesOf(in.getPool(), CANDIDATE_FOLLOW_UP);
        List<CandidatePoolItem> switches = candidatesOf(in.getPool(), CANDIDATE_SWITCH);
        List<CandidatePoolItem> entries = candidatesOf(in.getPool(), CANDIDATE_ENTRY);
        String nextStage = nextStageOf(plan, in.getCurrentStage());

        // 决策时覆盖度为本题提交前的状态；本题落库后当前阶段题数/覆盖/追问深度会变化，
        // 因此推进与上限判断基于"提交后"的有效值，避免每阶段实际多问 1 题（policy 2.4 规则2）。
        int currentCount = stageCount(coverage, in.getCurrentStage()) + 1;
        int effectiveFollowUpCount = followUpCount(coverage, in.getCurrentStage())
                + (currentIsFollowUp ? 1 : 0);
        List<String> effectiveCovered = new ArrayList<>(coveredTopics(coverage, in.getCurrentStage()));
        if (in.getAnsweredTopic() != null && !in.getAnsweredTopic().isBlank()
                && !effectiveCovered.contains(in.getAnsweredTopic())) {
            effectiveCovered.add(in.getAnsweredTopic());
        }
        // 规则3：达到单主题最大追问深度则禁止追问
        boolean depthLimited = currentPlan != null
                && effectiveFollowUpCount >= maxFollowUpDepth(currentPlan);

        // 规则2：全部阶段满足推进条件且总题量达到最少题量 → 结束（先于单阶段强制推进，
        // 保证最后一个阶段完成时正确结束而不是产生 NEXT_STAGE(null)）
        if (allStagesSatisfied(plan, coverage, in.getCurrentStage(), currentCount, effectiveCovered)
                && totalCount >= totalMin(plan)) {
            return finish(END_PLAN_COMPLETED, "全部阶段满足推进条件且总题量达到 " + totalMin(plan) + "，结束面试");
        }
        // 规则2：当前阶段题量达到 max_questions 必须推进
        if (currentPlan != null && currentCount >= maxQuestions(currentPlan)) {
            if (!entries.isEmpty()) {
                return nextStage(nextStage, entries.get(0), "当前阶段题量达到上限 " + maxQuestions(currentPlan));
            }
            return finish(END_NO_VALID_QUESTION, "当前阶段题量已达上限但无下一阶段入口候选，结束面试");
        }

        // 规则5：正常流程 —— 高质量且可追问 → 追问；否则同阶段有换题候选 → 换题
        if (!depthLimited && in.getScore() >= 70 && !followUps.isEmpty()) {
            return followUp(followUps.get(0), "回答质量良好（得分 " + in.getScore() + "）且未达追问深度，选择追问候选");
        }
        if (!switches.isEmpty()) {
            CandidatePoolItem item = pickSwitch(switches,
                    missingTopics(coverage, in.getCurrentStage()), in.getCurrentTopic());
            return switchTopic(item, "回答质量"
                    + (in.getScore() < 40 ? "差（得分 " + in.getScore() + "）" : "中等（得分 " + in.getScore() + "）")
                    + "，切换同阶段主题");
        }

        // 规则4：追问与换题候选都为空 → 使用入口候选推进；入口也空则结束（候选池耗尽）
        if (followUps.isEmpty() && switches.isEmpty()) {
            if (!entries.isEmpty()) {
                return nextStage(nextStage, entries.get(0), "追问与换题候选为空，使用下一阶段入口候选");
            }
            return finish(END_NO_VALID_QUESTION, "候选池耗尽（含下一阶段入口），结束面试");
        }

        // 规则2：当前阶段覆盖充分且达到最少题量 → 可推进（仅当存在入口候选时，
        // 否则交由兜底用追问候选保持面试，避免 NEXT_STAGE 缺下一题）
        if (currentPlan != null && currentCount >= minQuestions(currentPlan)
                && containsAll(effectiveCovered, requiredTopics(currentPlan)) && !entries.isEmpty()) {
            return nextStage(nextStage, entries.get(0), "本阶段必覆盖主题已覆盖且达到最少题量，进入下一阶段");
        }

        // 兜底：仍有追问候选则追问（无换题/入口候选时复用追问保持面试推进），
        // 否则候选池耗尽结束。此分支保证非 FINISH 决策必带候选（不变量）。
        if (!depthLimited && !followUps.isEmpty()) {
            return followUp(followUps.get(0), "无可用换题/入口候选，复用追问候选保持面试推进");
        }
        return finish(END_NO_VALID_QUESTION, "候选池耗尽且无下一阶段入口，结束面试");
    }

    // ==================== 决策工厂 ====================

    private Decision followUp(CandidatePoolItem item, String reason) {
        Decision d = base(ACTION_FOLLOW_UP, reason);
        d.setSelectedCandidate(item);
        return d;
    }

    private Decision switchTopic(CandidatePoolItem item, String reason) {
        Decision d = base(ACTION_SWITCH_TOPIC, reason);
        d.setSelectedCandidate(item);
        return d;
    }

    private Decision nextStage(String nextStage, CandidatePoolItem entry, String reason) {
        Decision d = base(ACTION_NEXT_STAGE, reason);
        d.setNextStage(nextStage);
        d.setSelectedCandidate(entry);
        return d;
    }

    private Decision finish(String endReason, String reason) {
        Decision d = base(ACTION_FINISH, reason);
        d.setEndReason(endReason);
        return d;
    }

    private Decision base(String action, String reason) {
        Decision d = new Decision();
        d.setNextAction(action);
        d.setDecisionReason(reason);
        return d;
    }

    // ==================== 私有辅助 ====================

    private List<CandidatePoolItem> candidatesOf(List<CandidatePoolItem> pool, String type) {
        List<CandidatePoolItem> result = new ArrayList<>();
        for (CandidatePoolItem item : pool) {
            if (type.equals(item.getCandidateType())) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 同阶段换题：优先选中缺失主题的候选（policy 3.4），其次避开当前主题，最后取首个。
     */
    private CandidatePoolItem pickSwitch(List<CandidatePoolItem> switches,
            List<String> missingTopics, String currentTopic) {
        for (CandidatePoolItem item : switches) {
            if (missingTopics.contains(item.getTopic())) {
                return item;
            }
        }
        for (CandidatePoolItem item : switches) {
            if (!item.getTopic().equals(currentTopic)) {
                return item;
            }
        }
        return switches.get(0);
    }

    // ==================== JSON 解析 ====================

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            log.warn("阶段计划/覆盖度 JSON 解析失败，按空处理，error={}", exception.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode planStage(JsonNode plan, String stage) {
        if (stage == null) {
            return null;
        }
        for (JsonNode item : plan.path("stages")) {
            if (stage.equals(item.path("stage").asText())) {
                return item;
            }
        }
        return null;
    }

    private String nextStageOf(JsonNode plan, String currentStage) {
        String[] order = {"BASIC", "PROJECT", "SCENARIO"};
        int index = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(currentStage)) {
                index = i;
                break;
            }
        }
        return index >= 0 && index + 1 < order.length ? order[index + 1] : null;
    }

    private int totalMin(JsonNode plan) {
        return plan.path("total_min_questions").asInt(Integer.MAX_VALUE);
    }

    private int totalMax(JsonNode plan) {
        return plan.path("total_max_questions").asInt(Integer.MAX_VALUE);
    }

    private int minQuestions(JsonNode stage) {
        return stage.path("min_questions").asInt(0);
    }

    private int maxQuestions(JsonNode stage) {
        return stage.path("max_questions").asInt(Integer.MAX_VALUE);
    }

    private int maxFollowUpDepth(JsonNode stage) {
        return stage.path("max_follow_up_depth").asInt(0);
    }

    private JsonNode stageCoverage(JsonNode coverage, String stage) {
        JsonNode node = stage == null ? null : coverage.get(stage);
        return node != null && node.isObject() ? node : objectMapper.createObjectNode();
    }

    private int stageCount(JsonNode coverage, String stage) {
        return stageCoverage(coverage, stage).path("question_count").asInt(0);
    }

    private int followUpCount(JsonNode coverage, String stage) {
        return stageCoverage(coverage, stage).path("current_topic_follow_up_count").asInt(0);
    }

    private List<String> coveredTopics(JsonNode coverage, String stage) {
        List<String> result = new ArrayList<>();
        stageCoverage(coverage, stage).path("covered_topics").forEach(n -> result.add(n.asText()));
        return result;
    }

    private List<String> missingTopics(JsonNode coverage, String stage) {
        List<String> result = new ArrayList<>();
        stageCoverage(coverage, stage).path("missing_topics").forEach(n -> result.add(n.asText()));
        return result;
    }

    private List<String> requiredTopics(JsonNode stage) {
        List<String> result = new ArrayList<>();
        stage.path("required_topics").forEach(n -> result.add(n.asText()));
        return result;
    }

    private boolean containsAll(List<String> covered, List<String> required) {
        for (String topic : required) {
            if (!covered.contains(topic)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 阶段满足推进条件：题量达到 max，或必覆盖主题全部覆盖且题量达到 min。
     * count/covered 由调用方传入（当前阶段使用本题提交后的有效值，其他阶段用覆盖度现值）。
     */
    private boolean stageSatisfied(JsonNode stage, int count, List<String> covered) {
        return count >= maxQuestions(stage)
                || (containsAll(covered, requiredTopics(stage)) && count >= minQuestions(stage));
    }

    /**
     * 全部阶段满足推进条件；当前阶段按本题提交后的有效值（题量 +1、主题并入覆盖）判断。
     */
    private boolean allStagesSatisfied(JsonNode plan, JsonNode coverage, String currentStage,
            int currentCount, List<String> effectiveCovered) {
        for (JsonNode stage : plan.path("stages")) {
            String name = stage.path("stage").asText();
            boolean isCurrent = name.equals(currentStage);
            int count = isCurrent ? currentCount : stageCount(coverage, name);
            List<String> covered = isCurrent ? effectiveCovered : coveredTopics(coverage, name);
            if (!stageSatisfied(stage, count, covered)) {
                return false;
            }
        }
        return true;
    }
}
