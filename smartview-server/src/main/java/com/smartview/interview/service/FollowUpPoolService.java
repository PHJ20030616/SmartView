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
 * - mergeFollowUps：回答提交时（5.4 接入）把追问候选并入同一 Redis key
 * - getPool：决策时读取候选池，Redis 缺失/过期/解析失败时按
 *   interview-policy.md 3.5 重建：① 最近 5 分钟决策快照 ② 同步调 FastAPI 重生成 ③ 空
 *
 * 关键设计：
 * 1. Redis 只做候选池暂存，权威状态在 MySQL；快照由 5.4 的 StagePolicyEngine 写入
 *    answer_evaluation.candidate_pool_snapshot_json（本类只读取）
 * 2. key 格式 interview:candidate_pool:{sessionId}:{questionId}:{currentStage}
 * 3. 预生成触发点在 InterviewSessionService.createSession 事务提交后（跨 Bean 调用使 @Async 生效）
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
            AiGenerateCandidatePoolRequest request = buildRequest(session, questionId, "PRE_GENERATED", null);
            AiGenerateCandidatePoolResponse response = aiInterviewClient.generateCandidatePool(request);
            if (Boolean.TRUE.equals(response.getSuccess())) {
                savePool(session, questionId, response.getCandidates());
                log.info("候选池预生成完成并写入 Redis sessionId={} questionId={} count={}",
                        sessionId, questionId, response.getCandidates() == null ? 0 : response.getCandidates().size());
            } else {
                log.warn("候选池预生成失败（业务失败）sessionId={} reason={}",
                        sessionId, response.getErrorMessage());
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
     * 读取候选池；Redis 缺失/过期/解析失败时按 3.5 重建。
     *
     * @param session    会话（调用方已加载）
     * @param questionId 待决策的问题 ID
     * @return 候选题列表；全部失败时返回空列表，由 StagePolicyEngine 降级
     */
    public List<CandidatePoolItem> getPool(InterviewSession session, Long questionId) {
        String key = key(session, questionId);
        List<CandidatePoolItem> pool = redisRepository.read(key);
        if (pool != null) {
            return pool;
        }
        return rebuild(session, questionId);
    }

    // ==================== 重建链路（interview-policy.md 3.5） ====================

    /**
     * 候选池重建：① 最近 5 分钟决策快照 ② 同步调 FastAPI 重生成 ③ 空。
     */
    private List<CandidatePoolItem> rebuild(InterviewSession session, Long questionId) {
        List<CandidatePoolItem> fromSnapshot = readRecentSnapshot(session.getId());
        if (fromSnapshot != null) {
            savePool(session, questionId, fromSnapshot);
            return fromSnapshot;
        }
        try {
            AiGenerateCandidatePoolRequest request = buildRequest(session, questionId, "PRE_GENERATED", null);
            AiGenerateCandidatePoolResponse response = aiInterviewClient.generateCandidatePool(request);
            if (Boolean.TRUE.equals(response.getSuccess())
                    && response.getCandidates() != null && !response.getCandidates().isEmpty()) {
                savePool(session, questionId, response.getCandidates());
                return response.getCandidates();
            }
            log.warn("候选池同步重建返回空 sessionId={}", session.getId());
        } catch (BusinessException exception) {
            log.warn("候选池同步重建失败 sessionId={} error={}", session.getId(), exception.getMessage());
        }
        return List.of();
    }

    /**
     * 读取最近一次决策快照中的候选池；快照缺失或超过 5 分钟返回 null。
     *
     * 快照格式（与 5.4 StagePolicyEngine 写入约定）：顶层 candidates 数组，
     * 元素为 CandidatePoolItem；本类只读取 candidates，不消费决策元数据。
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
            return items.isEmpty() ? null : items;
        } catch (IOException exception) {
            log.warn("候选池快照解析失败 sessionId={} error={}", sessionId, exception.getMessage());
            return null;
        }
    }

    // ==================== 私有辅助 ====================

    /**
     * 组装候选池生成请求：session 上下文 + 阶段计划/覆盖度（JSON→JsonNode）+ 历史主题。
     */
    private AiGenerateCandidatePoolRequest buildRequest(
            InterviewSession session, Long questionId, String poolType,
            AiGenerateCandidatePoolRequest.EvaluationFacts evaluationFacts) {
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
        request.setEvaluationFacts(evaluationFacts);
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
