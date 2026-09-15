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
 * 追问的得分门控（policy 3.3）由本引擎承担：追问候选与回答评估在 AI 服务并行生成，
 * 生成侧不再按得分过滤，因此引擎按得分选择候选类型（≥70 深挖 DEEP / 40-70 补缺口 GAP），
 * 并对弱答（&lt;40）一律判为不可用（见 {@link #pickFollowUp}）。
 *
 * 空池兜底（interview-policy.md 5.3）：候选池是尽力而为的缓存，读不到候选不等于
 * "面试没有题可问了"。因此规则4/兜底分支不再产出 FINISH(NO_VALID_QUESTION)，改为调用
 * {@link FallbackQuestionFactory} 生成模板化过渡题继续面试——把兜底方向从
 * "拿不到候选 → 结束会话"反转为"拿不到候选 → 出一道具名但相关性一般的确定性题"。
 * 连续兜底时主题与措辞按阶段题量轮转，避免连续出现字面相同的题；总题量上限在阶段计划
 * 缺失时按 {@link #DEFAULT_TOTAL_MAX_QUESTIONS} 兜底，保证面试不会被兜底题拖成无限长。
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

    /**
     * 历史结束原因：候选池耗尽且无可用候选。
     *
     * 仅保留用于识别历史会话数据；引擎自 v1.2 起不再产出该值——空池一律改出
     * 模板化过渡题（见类注释），保证"缓存抖动"不会被误判成"面试正常结束"。
     */
    public static final String END_NO_VALID_QUESTION = "NO_VALID_QUESTION";

    /**
     * 候选类型常量：引用 {@link CandidatePoolItem} 上的契约常量，避免同一组字符串
     * 在引擎与模板工厂里各存一份（契约改名时容易只改一处）。
     */
    private static final String CANDIDATE_FOLLOW_UP = CandidatePoolItem.TYPE_FOLLOW_UP;
    private static final String CANDIDATE_SWITCH = CandidatePoolItem.TYPE_SAME_STAGE_SWITCH;
    private static final String CANDIDATE_ENTRY = CandidatePoolItem.TYPE_NEXT_STAGE_ENTRY;

    /** 阶段计划缺失或字段缺失时的总题量上限兜底（与 StagePlanBuilder 的计划默认值一致） */
    private static final int DEFAULT_TOTAL_MAX_QUESTIONS = 20;

    /**
     * 追问采用的得分门控（interview-policy.md 3.3）：
     * 得分 ≥70 才追问，且此时应使用"深挖（DEEP）"型候选。
     */
    private static final int FOLLOW_UP_SCORE_THRESHOLD = 70;

    /**
     * 弱答下限：得分 <40 一律不追问，改用换题/模板化过渡题。
     *
     * 该门控原先在 AI 服务生成侧（按得分决定是否生成追问），追问生成改为与评估并行后
     * 生成侧拿不到得分，因此必须由本引擎在决策时拦截，保证 policy 3.3 不变量。
     */
    private static final int WEAK_ANSWER_SCORE_THRESHOLD = 40;

    private final ObjectMapper objectMapper;
    private final FallbackQuestionFactory fallbackQuestionFactory;

    public StagePolicyEngine(ObjectMapper objectMapper, FallbackQuestionFactory fallbackQuestionFactory) {
        this.objectMapper = objectMapper;
        this.fallbackQuestionFactory = fallbackQuestionFactory;
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
     * → 规则3/5 正常流程 → 规则4 空池降级（含模板化兜底题）→ 规则2 当前阶段可推进 → 兜底。
     *
     * 不变量：非 FINISH 决策必然携带 selectedCandidate，事务层据此落库下一题；
     * 空池不再降级为 FINISH，而是产出模板化过渡题（FallbackQuestionFactory），
     * 因此"取得不到候选"这一降级路径不会结束面试。
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

        // 追问可用性（policy 3.3）：得分门控在决策侧执行 —— 追问候选与回答评估在 AI 服务
        // 并行生成，生成侧不再按得分过滤，低分回答同样可能带回候选，因此这里显式按得分
        // 选型（≥70 深挖 DEEP / 40-70 补缺口 GAP），并对弱答（<40）一律判为不可用。
        CandidatePoolItem followUpCandidate = pickFollowUp(followUps, in.getScore());

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
        // 保证最后一个阶段完成时正确结束而不是产生 NEXT_STAGE(null)）。
        // 注意这条判定只看阶段计划与覆盖度，与候选池无关：必覆盖主题已全部覆盖、各阶段
        // 题量均达 min_questions、总题量达 total_min_questions，就是计划意义上的"面试问完
        // 了"，此时池里有没有候选都不该继续出题（池非空时同样返回 PLAN_COMPLETED）。
        // 空池兜底只作用于"还需要继续出题"的路径，不改变计划完成条件。
        if (allStagesSatisfied(plan, coverage, in.getCurrentStage(), currentCount, effectiveCovered)
                && totalCount >= totalMin(plan)) {
            return finish(END_PLAN_COMPLETED, "全部阶段满足推进条件且总题量达到 " + totalMin(plan) + "，结束面试");
        }
        // 规则2：当前阶段题量达到 max_questions 必须推进
        if (currentPlan != null && currentCount >= maxQuestions(currentPlan)) {
            if (!entries.isEmpty()) {
                return nextStage(nextStage, entries.get(0), "当前阶段题量达到上限 " + maxQuestions(currentPlan));
            }
            return fallbackForcedAdvance(plan, in, nextStage, currentPlan);
        }

        // 规则5：正常流程 —— 高质量且可追问 → 追问；否则同阶段有换题候选 → 换题
        if (!depthLimited && in.getScore() >= FOLLOW_UP_SCORE_THRESHOLD && followUpCandidate != null) {
            return followUp(followUpCandidate, "回答质量良好（得分 " + in.getScore()
                    + "），选择追问候选（"
                    + describeFollowUpKind(followUpCandidate) + "）");
        }
        if (!switches.isEmpty()) {
            CandidatePoolItem item = pickSwitch(switches,
                    missingTopics(coverage, in.getCurrentStage()), in.getCurrentTopic());
            return switchTopic(item, "回答质量"
                    + (in.getScore() < WEAK_ANSWER_SCORE_THRESHOLD
                            ? "差（得分 " + in.getScore() + "）" : "中等（得分 " + in.getScore() + "）")
                    + "，切换同阶段主题");
        }

        // 规则4：可用追问与换题候选都为空 → 使用入口候选推进；入口也空则出模板化过渡题
        // 注意这里按"追问是否可用"判断（弱答即使池里有追问候选也视为不可用）
        if (followUpCandidate == null && switches.isEmpty()) {
            if (!entries.isEmpty()) {
                return nextStage(nextStage, entries.get(0), "追问与换题候选为空，使用下一阶段入口候选");
            }
            return fallbackDecision(plan, in, nextStage, currentPlan, currentCount, effectiveCovered,
                    "追问、换题与下一阶段入口候选均为空");
        }

        // 规则2：当前阶段覆盖充分且达到最少题量 → 可推进（仅当存在入口候选时，
        // 否则交由兜底用追问候选保持面试，避免 NEXT_STAGE 缺下一题）
        if (currentPlan != null && currentCount >= minQuestions(currentPlan)
                && containsAll(effectiveCovered, requiredTopics(currentPlan)) && !entries.isEmpty()) {
            return nextStage(nextStage, entries.get(0), "本阶段必覆盖主题已覆盖且达到最少题量，进入下一阶段");
        }

        // 兜底：仍有可用追问候选则追问（无换题/入口候选时复用追问保持面试推进），
        // 否则出模板化过渡题继续面试。此分支保证非 FINISH 决策必带候选（不变量）。
        if (!depthLimited && followUpCandidate != null) {
            return followUp(followUpCandidate, "无可用换题/入口候选，复用追问候选保持面试推进（"
                    + describeFollowUpKind(followUpCandidate) + "）");
        }
        return fallbackDecision(plan, in, nextStage, currentPlan, currentCount, effectiveCovered,
                "候选池无可用候选");
    }

    // ==================== 空池模板化兜底 ====================

    /**
     * 规则2"当前阶段达题量上限必须推进"的兜底：候选池里没有下一阶段入口候选。
     *
     * 若仍有下一阶段，则用模板化入口题推进——否则整场面试会因为少一道候选而提前结束；
     * 若已是最后一个阶段，说明各阶段题量均已达上限（例如计划里 total_min_questions
     * 大于各阶段 max_questions 之和这类不自洽配置），按"计划完成"结束更贴近事实，
     * 也更便于事后定位计划配置问题。
     */
    private Decision fallbackForcedAdvance(JsonNode plan, DecisionInput in, String nextStage,
            JsonNode currentPlan) {
        if (nextStage == null) {
            return finish(END_PLAN_COMPLETED, "当前阶段题量达到上限 " + maxQuestions(currentPlan)
                    + " 且无下一阶段，按计划完成结束面试");
        }
        return nextStage(nextStage,
                fallbackQuestionFactory.createEntryFallback(nextStage, firstRequiredTopic(plan, nextStage)),
                "当前阶段题量达到上限 " + maxQuestions(currentPlan) + " 且无入口候选，改用模板化过渡题推进");
    }

    /**
     * 候选池无可用候选时的模板化兜底（interview-policy.md 5.3）。
     *
     * 选择顺序体现"模板题也要服务阶段计划"的取舍：
     * ① 当前阶段还有未覆盖的必覆盖主题 → 直接针对该主题出题，避免兜底题破坏覆盖度要求；
     * ② 当前阶段已满足推进条件（题量达 max，或必覆盖主题齐备且达到最少题量）→ 出下一阶段入口题；
     * ③ 其余情况 → 在必覆盖主题间轮转、措辞按下标轮转，追加一道模板题避免面试中断
     *    （题量上限仍由规则1约束；轮转是为了避免连续兜底时反复问同一道题）。
     *
     * @param currentCount     本题提交后的阶段有效题量，同时作为主题/措辞的轮转下标
     * @param effectiveCovered 本题提交后的阶段有效覆盖主题
     * @param trigger          触发原因，写入决策原因便于统计兜底触发率
     */
    private Decision fallbackDecision(JsonNode plan, DecisionInput in, String nextStage,
            JsonNode currentPlan, int currentCount, List<String> effectiveCovered, String trigger) {
        String currentStage = in.getCurrentStage();
        if (currentPlan != null) {
            for (String topic : requiredTopics(currentPlan)) {
                if (!effectiveCovered.contains(topic)) {
                    return switchTopic(
                            fallbackQuestionFactory.createSwitchFallback(currentStage, topic, currentCount),
                            trigger + "，改用模板化过渡题补齐当前阶段必覆盖主题「" + topic + "」");
                }
            }
            if (nextStage != null && stageSatisfied(currentPlan, currentCount, effectiveCovered)) {
                return nextStage(nextStage,
                        fallbackQuestionFactory.createEntryFallback(
                                nextStage, firstRequiredTopic(plan, nextStage)),
                        trigger + "，本阶段已满足推进条件，改用下一阶段入口模板化过渡题");
            }
        }
        // 继续在当前阶段出兜底题：主题按阶段题量在必覆盖主题间轮转（全部已覆盖时用它们
        // 轮转能避免反复问同一个主题），措辞用 variant 轮转，保证连续两道兜底题不完全相同。
        String topic = rotateTopic(currentPlan, currentCount);
        CandidatePoolItem fallback = fallbackQuestionFactory.createSwitchFallback(
                currentStage, topic == null ? in.getCurrentTopic() : topic, currentCount);
        return switchTopic(fallback,
                trigger + "，改用模板化过渡题在当前主题「" + fallback.getTopic() + "」上继续");
    }

    /**
     * 在当前阶段的必覆盖主题间轮转取主题（按阶段题量取模）；阶段计划缺失或未配置主题时返回 null，
     * 由调用方回退到会话当前主题。
     */
    private String rotateTopic(JsonNode currentPlan, int currentCount) {
        List<String> topics = requiredTopics(currentPlan);
        return topics.isEmpty() ? null : topics.get(Math.floorMod(currentCount, topics.size()));
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

    /**
     * 按得分选择要采用的追问候选（policy 3.3 的得分门控在决策侧执行）。
     *
     * 规则：得分 &lt;40 → 不可用（弱答直接追问没有意义，改为换题/过渡题）；
     * 得分 ≥70 → 优先"深挖（DEEP）"型；40~70 → 优先"补缺口（GAP）"型。
     * 优先类型缺失时（单条生成失败、或 Redis 里的旧数据没有 followUpKind）回退为首个可用
     * 追问候选：候选池是尽力而为的缓存，AI 服务本就按"单目标失败降级"处理，这里保持
     * "有候选就用"的旧行为，避免因缺少类型标注而退化成模板化过渡题。
     *
     * 该软回退依赖 AI 侧生成顺序固定为 [GAP, DEEP]（stage_controller 的顺序约定 + 候选池
     * 保序封顶）：两型都在时不会用到它；只有一型时才取那一型，这正是期望行为。
     *
     * @param followUps 追问候选（保持生成侧顺序）
     * @param score     当前回答得分
     * @return 采用的追问候选；不可用时返回 null
     */
    private CandidatePoolItem pickFollowUp(List<CandidatePoolItem> followUps, int score) {
        if (score < WEAK_ANSWER_SCORE_THRESHOLD || followUps.isEmpty()) {
            return null;
        }
        String preferred = score >= FOLLOW_UP_SCORE_THRESHOLD
                ? CandidatePoolItem.KIND_DEEP : CandidatePoolItem.KIND_GAP;
        for (CandidatePoolItem item : followUps) {
            if (preferred.equals(item.getFollowUpKind())) {
                return item;
            }
        }
        // 走到这里有两种情况：①另一型生成失败（正常降级，取那一型即可）；
        // ②候选整体没有类型标注（question_generator 会 warn，这里也留一条，便于按
        // "软回退率"判断是不是整条链路的 followUpKind 丢了）。两者都只写日志、不改行为。
        CandidatePoolItem fallback = followUps.get(0);
        if (fallback.getFollowUpKind() == null) {
            log.warn("追问候选缺少 followUpKind（软回退取首个候选）score={} topic={} candidateCount={}",
                    score, fallback.getTopic(), followUps.size());
        } else {
            log.debug("首选追问类型 {} 缺失，软回退到 {} score={}",
                    preferred, fallback.getFollowUpKind(), score);
        }
        return fallback;
    }

    /**
     * 追问类型的中文描述，用于决策理由（审计时能看出采用了几型候选）。
     */
    private String describeFollowUpKind(CandidatePoolItem item) {
        if (CandidatePoolItem.KIND_DEEP.equals(item.getFollowUpKind())) {
            return "深挖型";
        }
        if (CandidatePoolItem.KIND_GAP.equals(item.getFollowUpKind())) {
            return "补缺口型";
        }
        return "类型未标注";
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
        // 计划缺失时取"永不满足"：宁可让 total_max 兜住上限，也不要凭空提前结束面试
        return plan.path("total_min_questions").asInt(Integer.MAX_VALUE);
    }

    private int totalMax(JsonNode plan) {
        // 计划缺失/畸形（stage_plan_json 解析失败或字段缺失）时不能退回 Integer.MAX_VALUE：
        // 那样规则1 永不触发，配合空池模板题会让面试无限出题。这里按 StagePlanBuilder 的
        // 计划默认值兜底，保证任何情况下面试都有确定的题量上限。
        return plan.path("total_max_questions").asInt(DEFAULT_TOTAL_MAX_QUESTIONS);
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
        if (stage == null) {
            // 阶段计划缺失或当前阶段不在计划内：按"无必覆盖主题"处理，
            // 让兜底模板题仍能产出题面，而不是在决策阶段抛空指针
            return result;
        }
        stage.path("required_topics").forEach(n -> result.add(n.asText()));
        return result;
    }

    /**
     * 取某阶段的首个必覆盖主题；阶段不存在或未配置主题时返回 null，
     * 由 FallbackQuestionFactory 回退到阶段默认主题名。
     */
    private String firstRequiredTopic(JsonNode plan, String stage) {
        if (stage == null) {
            return null;
        }
        List<String> topics = requiredTopics(planStage(plan, stage));
        return topics.isEmpty() ? null : topics.get(0);
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
