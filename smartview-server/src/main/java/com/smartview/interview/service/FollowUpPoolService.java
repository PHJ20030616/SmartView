package com.smartview.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.ai.client.AiGenerateCandidatePoolRequest;
import com.smartview.ai.client.AiGenerateCandidatePoolResponse;
import com.smartview.ai.client.AiInterviewClient;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.interview.entity.AnswerEvaluation;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.interview.model.CandidatePoolItem;
import com.smartview.infra.redis.CandidatePoolRedisRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 候选问题池服务。
 *
 * 功能说明：
 * - preGenerateAsync：提问落库后异步调用 FastAPI 生成预生成候选池（同阶段换题 +
 *   下一阶段入口），写入 Redis；候选池是尽力而为的缓存，失败不阻断主链路
 * - mergeFollowUps：回答提交时把追问候选并入同一 Redis key
 * - readPool：决策时读取候选池，**零 LLM 调用**：Redis → 最近 5 分钟决策快照 → 空
 *
 * 为什么提交路径不再同步重生成候选池（interview-policy.md 3.5）：
 * 同步重生成会把 2~6 次 LLM 调用（单次实测均值 8.7s）塞进"用户点提交后正在等待的
 * 那个 HTTP 请求"里，实测最坏把提交耗时推到 43.8s，超过前端 15s 上限后表现为
 * "提交失败"，用户重按虽被幂等拦住落库、但仍白烧一次 LLM 配额。候选池本就允许缺失
 * （缺失时由 StagePolicyEngine 出模板化过渡题），因此这里只读不生成；下一题的候选池
 * 由提交事务提交后的 preGenerateAsync 补齐。
 *
 * 关键设计：
 * 1. Redis 只做候选池暂存，权威状态在 MySQL；快照由 InterviewAnswerTxService 落库时写入
 *    answer_evaluation.candidate_pool_snapshot_json（本类只读取）
 * 2. key 格式 interview:candidate_pool:{sessionId}:{questionId}:{currentStage}
 * 3. 预生成触发点在 InterviewSessionService.createSession 与 InterviewAnswerService
 *    落库事务提交后（跨 Bean 调用使 @Async 生效）
 *
 * @author SmartView Team
 * @since 2026-08-07
 */
@Slf4j
@Service
public class FollowUpPoolService {

    private static final String KEY_TEMPLATE = "interview:candidate_pool:%d:%d:%s";
    /** 快照新鲜度阈值：创建时间在 5 分钟内才直接复用（interview-policy.md 3.5） */
    private static final long SNAPSHOT_FRESH_MINUTES = 5;
    /** 预生成请求携带的历史主题数量上限 */
    private static final int HISTORY_TOPIC_LIMIT = 20;

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final AnswerEvaluationMapper answerEvaluationMapper;
    private final CandidatePoolRedisRepository redisRepository;
    private final AiInterviewClient aiInterviewClient;
    private final ObjectMapper objectMapper;

    public FollowUpPoolService(
            InterviewSessionMapper sessionMapper,
            InterviewQuestionMapper questionMapper,
            AnswerEvaluationMapper answerEvaluationMapper,
            CandidatePoolRedisRepository redisRepository,
            AiInterviewClient aiInterviewClient,
            ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.answerEvaluationMapper = answerEvaluationMapper;
        this.redisRepository = redisRepository;
        this.aiInterviewClient = aiInterviewClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步生成预生成候选池并写入 Redis（提问落库事务提交后调用）。
     *
     * @param sessionId  会话 ID
     * @param questionId 已提问的问题 ID
     */
    @Async("candidatePoolExecutor")
    public void preGenerateAsync(Long sessionId, Long questionId) {
        try {
            InterviewSession session = sessionMapper.selectById(sessionId);
            if (session == null) {
                log.warn("候选池预生成跳过：会话不存在 sessionId={}", sessionId);
                return;
            }
            AiGenerateCandidatePoolRequest request = buildRequest(session, questionId, "PRE_GENERATED");
            AiGenerateCandidatePoolResponse response = aiInterviewClient.generateCandidatePool(request);
            // candidates=null 视为失败：避免向 Redis 写入 "null" 字面量（读取侧虽可自愈，但会污染缓存）
            if (Boolean.TRUE.equals(response.getSuccess()) && response.getCandidates() != null) {
                savePool(session, questionId, response.getCandidates());
                log.info("候选池预生成完成并写入 Redis sessionId={} questionId={} count={}",
                        sessionId, questionId, response.getCandidates().size());
            } else {
                log.warn("候选池预生成失败或无候选 sessionId={} questionId={} reason={}",
                        sessionId, questionId,
                        Boolean.TRUE.equals(response.getSuccess())
                                ? "候选列表为空" : response.getErrorMessage());
            }
        } catch (BusinessException exception) {
            // AI 服务不可用/鉴权错误：候选池可降级，仅记录不抛出
            log.warn("候选池预生成调用失败 sessionId={} error={}", sessionId, exception.getMessage());
        } catch (Exception exception) {
            log.error("候选池预生成异常 sessionId={}", sessionId, exception);
        }
    }

    /**
     * 写入候选池到 Redis（带 30 分钟 TTL）。
     */
    public void savePool(InterviewSession session, Long questionId, List<CandidatePoolItem> candidates) {
        redisRepository.save(key(session, questionId), candidates);
    }

    /**
     * 把追问候选池并入同一 Redis key（回答提交后由 5.4 调用）。
     */
    public void mergeFollowUps(InterviewSession session, Long questionId, List<CandidatePoolItem> followUps) {
        redisRepository.mergeFollowUps(key(session, questionId), followUps);
    }

    /**
     * 读取候选池（决策用，零 LLM 调用）。
     *
     * 读取顺序（interview-policy.md 3.5）：Redis 命中直接返回 → 最近 5 分钟决策快照
     * （回写 Redis 后返回）→ 返回空列表。空列表不是错误：调用方的 StagePolicyEngine
     * 会改用模板化过渡题维持面试，不会因缓存缺失而结束会话。
     *
     * @param session    会话（调用方已加载）
     * @param questionId 待决策的问题 ID
     * @return 候选题列表；无缓存且快照不可用时为空列表
     */
    public List<CandidatePoolItem> readPool(InterviewSession session, Long questionId) {
        String key = key(session, questionId);
        List<CandidatePoolItem> pool = redisRepository.read(key);
        if (pool != null) {
            return pool;
        }
        List<CandidatePoolItem> fromSnapshot = readRecentSnapshot(session.getId());
        if (fromSnapshot != null) {
            // 快照命中也写回 Redis，避免同一道题在短时间内反复查库
            savePool(session, questionId, fromSnapshot);
            return fromSnapshot;
        }
        log.warn("候选池与决策快照均不可用，交由决策引擎出模板化过渡题 sessionId={} questionId={}",
                session.getId(), questionId);
        return List.of();
    }

    /**
     * 读取最近一次决策快照中的候选池；快照缺失或超过 5 分钟返回 null。
     *
     * 快照格式（与 InterviewAnswerTxService.buildSnapshotJson 写入约定）：顶层 candidates
     * 数组，元素为 CandidatePoolItem；本类只读取 candidates，不消费决策元数据。
     *
     * 注意：快照来自上一题的决策，其 FOLLOW_UP 候选是针对上一题回答生成的，
     * 跨题复用会问出与当前回答无关的追问，因此重建时剔除 FOLLOW_UP 类型；
     * 同阶段换题 / 下一阶段入口候选与题目无关，可安全复用。
     */
    private List<CandidatePoolItem> readRecentSnapshot(Long sessionId) {
        AnswerEvaluation latest = answerEvaluationMapper.selectOne(
                new LambdaQueryWrapper<AnswerEvaluation>()
                        .eq(AnswerEvaluation::getSessionId, sessionId)
                        .isNotNull(AnswerEvaluation::getCandidatePoolSnapshotJson)
                        .ne(AnswerEvaluation::getCandidatePoolSnapshotJson, "")
                        .orderByDesc(AnswerEvaluation::getId)
                        .last("LIMIT 1"));
        if (latest == null || latest.getCreatedAt() == null
                || latest.getCreatedAt().plusMinutes(SNAPSHOT_FRESH_MINUTES).isBefore(LocalDateTime.now())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(latest.getCandidatePoolSnapshotJson());
            JsonNode candidates = node.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                return null;
            }
            List<CandidatePoolItem> items = objectMapper.readValue(
                    candidates.traverse(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<CandidatePoolItem>>() {
                    });
            // 剔除针对上一题回答生成的追问候选（跨题复用语义错位）
            items.removeIf(item -> "FOLLOW_UP".equals(item.getCandidateType()));
            return items.isEmpty() ? null : items;
        } catch (IOException exception) {
            log.warn("候选池快照解析失败 sessionId={} error={}", sessionId, exception.getMessage());
            return null;
        }
    }

    // ==================== 私有辅助 ====================

    /**
     * 组装候选池生成请求：session 上下文 + 阶段计划/覆盖度（JSON→JsonNode）+ 历史主题。
     *
     * 不携带 evaluationFacts：追问池（FOLLOW_UP）已改由回答提交时的 /evaluate 响应
     * 直接提供，本类只负责与答案无关的预生成池（同阶段换题 + 下一阶段入口）。
     */
    private AiGenerateCandidatePoolRequest buildRequest(
            InterviewSession session, Long questionId, String poolType) {
        AiGenerateCandidatePoolRequest request = new AiGenerateCandidatePoolRequest();
        request.setSessionId(String.valueOf(session.getId()));
        request.setQuestionId(String.valueOf(questionId));
        request.setRoleDirection(session.getRoleDirection());
        request.setPoolType(poolType);
        request.setCurrentStage(session.getCurrentStage());
        request.setStagePlan(parseJson(session.getStagePlanJson()));
        request.setStageCoverage(parseJson(session.getStageCoverageJson()));
        AiGenerateCandidatePoolRequest.SessionContext context =
                new AiGenerateCandidatePoolRequest.SessionContext();
        context.setCurrentTopic(session.getCurrentTopic());
        context.setQuestionCount(session.getQuestionCount());
        request.setSessionContext(context);
        request.setHistoryTopics(loadHistoryTopics(session.getId()));
        request.setTraceId(TraceIdContext.currentTraceId());
        return request;
    }

    /**
     * 加载会话最近已问主题（去重、限量），用于候选生成避免重复。
     */
    private List<String> loadHistoryTopics(Long sessionId) {
        List<InterviewQuestion> questions = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .isNotNull(InterviewQuestion::getTopic)
                        .ne(InterviewQuestion::getTopic, "")
                        .orderByDesc(InterviewQuestion::getId)
                        .last("LIMIT " + HISTORY_TOPIC_LIMIT));
        Set<String> topics = new LinkedHashSet<>();
        for (InterviewQuestion question : questions) {
            topics.add(question.getTopic());
        }
        return new ArrayList<>(topics);
    }

    /**
     * 把业务 JSON 解析为 JsonNode 透传；空值返回空对象，避免请求体字段为 null。
     */
    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            log.warn("候选池请求 JSON 解析失败，按空对象透传，error={}", exception.getMessage());
            return new com.fasterxml.jackson.databind.node.ObjectNode(objectMapper.getNodeFactory());
        }
    }

    /**
     * 拼接 Redis key：interview:candidate_pool:{sessionId}:{questionId}:{currentStage}。
     */
    private String key(InterviewSession session, Long questionId) {
        return String.format(KEY_TEMPLATE, session.getId(), questionId, session.getCurrentStage());
    }
}
