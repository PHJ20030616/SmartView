package com.smartview.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.ai.client.AiEvaluateAnswerRequest;
import com.smartview.ai.client.AiEvaluateAnswerResponse;
import com.smartview.ai.client.AiGenerateCandidatePoolRequest;
import com.smartview.ai.client.AiInterviewClient;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.SubmitAnswerData;
import com.smartview.generated.web.model.SubmitAnswerRequest;
import com.smartview.interview.dto.InterviewSessionDtoMapper;
import com.smartview.interview.engine.StagePolicyEngine;
import com.smartview.interview.entity.AnswerEvaluation;
import com.smartview.interview.entity.InterviewAnswer;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.enums.InterviewSessionStatus;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewAnswerMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.interview.model.CandidatePoolItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答提交编排服务。
 *
 * 流程（docs/interview-policy.md 4.x）：
 * ① 幂等查询 request_id → 命中直接返回既有结果，不重复推进；
 * ② 校验会话归属/状态、当前题目一致性；
 * ③ 事务外调用 FastAPI 评估（避免 10s HTTP 占住 DB 连接，幂等+唯一索引兜底竞态）；
 * ④ 追问候选并入 Redis 候选池；
 * ⑤ 读取合并候选池（Redis → 快照 → 同步重生成，降级不丢回答）；
 * ⑥ StagePolicyEngine 确定性决策；
 * ⑦ 单事务落库推进（InterviewAnswerTxService）；
 * ⑧ 事务提交后预热下一题候选池。
 *
 * 本服务不持有 @Transactional：事务边界在 InterviewAnswerTxService，
 * 保证评估调用发生在事务外。
 *
 * @author SmartView Team
 * @since 2026-08-09
 */
@Slf4j
@Service
public class InterviewAnswerService {

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final AnswerEvaluationMapper answerEvaluationMapper;
    private final AiInterviewClient aiInterviewClient;
    private final FollowUpPoolService followUpPoolService;
    private final StagePolicyEngine stagePolicyEngine;
    private final InterviewAnswerTxService answerTxService;
    private final InterviewSessionDtoMapper dtoMapper;
    private final ObjectMapper objectMapper;

    public InterviewAnswerService(
            InterviewSessionMapper sessionMapper,
            InterviewQuestionMapper questionMapper,
            InterviewAnswerMapper answerMapper,
            AnswerEvaluationMapper answerEvaluationMapper,
            AiInterviewClient aiInterviewClient,
            FollowUpPoolService followUpPoolService,
            StagePolicyEngine stagePolicyEngine,
            InterviewAnswerTxService answerTxService,
            InterviewSessionDtoMapper dtoMapper,
            ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.answerEvaluationMapper = answerEvaluationMapper;
        this.aiInterviewClient = aiInterviewClient;
        this.followUpPoolService = followUpPoolService;
        this.stagePolicyEngine = stagePolicyEngine;
        this.answerTxService = answerTxService;
        this.dtoMapper = dtoMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交回答（幂等优先）。
     *
     * @param userId    当前登录用户 ID
     * @param sessionId 面试会话 ID
     * @param request   提交请求（questionId/answerText/requestId/durationSeconds）
     * @return 响应数据：回答 ID、评估、下一题（结束为空）、会话状态
     */
    public SubmitAnswerData submitAnswer(Long userId, Long sessionId, SubmitAnswerRequest request) {
        validateRequest(request);

        // ① 会话归属校验（先于幂等返回，防止携带他人会话 ID + 已知 request_id 越权读取回答）
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "面试会话不存在");
        }
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该面试会话", HttpStatus.FORBIDDEN);
        }

        // ② 幂等：request_id 已存在 → 返回既有结果。必须在当前题校验前判断，
        //    因为首次提交已推进会话，重试时 current_question_id 已变化。
        InterviewAnswer existing = answerMapper.selectOne(
                new LambdaQueryWrapper<InterviewAnswer>()
                        .eq(InterviewAnswer::getRequestId, request.getRequestId().toString()));
        if (existing != null) {
            return buildIdempotentResult(sessionId, existing);
        }

        // ③ 会话状态与当前题目校验（提交过期或非当前题目被拒绝）
        if (!InterviewSessionStatus.IN_PROGRESS.getCode().equals(session.getStatus())) {
            throw new BusinessException(ResponseCode.CONFLICT, "会话不在面试进行中，无法提交回答", HttpStatus.CONFLICT);
        }
        InterviewQuestion current = requireCurrentQuestion(session, request.getQuestionId());

        // ④ 事务外调用 FastAPI 评估；失败不落库，允许用户重试（policy 5.2）
        AiEvaluateAnswerResponse eval = aiInterviewClient.evaluateAnswer(
                buildEvaluateRequest(session, current, request));
        if (!Boolean.TRUE.equals(eval.getSuccess())) {
            String reason = eval.getErrorMessage() == null ? "未知错误" : eval.getErrorMessage();
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "回答评估失败：" + reason + "，请重试");
        }

        // ⑤ 追问候选并入 Redis 候选池（同 key 追加 FOLLOW_UP 类型）
        List<CandidatePoolItem> followUps = filterItems(eval.getFollowUpCandidates());
        if (!followUps.isEmpty()) {
            followUpPoolService.mergeFollowUps(session, current.getId(), followUps);
        }

        // ⑥ 读取合并候选池：Redis 命中优先；缺失时按 3.5 重建（快照/同步重生成），
        //    并携带评估事实以重建追问池，保证回答不因候选池缺失而丢失
        List<CandidatePoolItem> pool = followUpPoolService.getPool(
                session, current.getId(), toFacts(session, current, request, eval));

        // ⑦ 确定性决策（policy 2.4）
        int consecutiveWeak = computeConsecutiveWeak(session.getId(), eval);
        StagePolicyEngine.Decision decision = stagePolicyEngine.decide(
                buildDecisionInput(session, current, eval, pool, consecutiveWeak));

        // ⑧ 单事务落库推进（乐观锁在事务内校验）
        SubmitAnswerData result = answerTxService.persist(
                userId, session, current, request, eval, decision, pool);

        // ⑨ 事务提交后预热下一题候选池（低延迟路径，@Async 跨 Bean 生效）
        if (result.getNextQuestion() != null) {
            followUpPoolService.preGenerateAsync(sessionId,
                    Long.parseLong(result.getNextQuestion().getId()));
        }
        return result;
    }

    // ==================== 私有辅助 ====================

    private void validateRequest(SubmitAnswerRequest request) {
        if (request == null || request.getRequestId() == null
                || request.getAnswerText() == null || request.getAnswerText().isBlank()) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "回答文本与 request_id 不能为空");
        }
        if (request.getRequestId().toString().length() > 64) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "request_id 超出长度限制");
        }
    }

    /**
     * 幂等结果：读取该次回答的评估、被选中的下一题，按决策推导会话状态。
     */
    private SubmitAnswerData buildIdempotentResult(Long sessionId, InterviewAnswer existing) {
        if (!existing.getSessionId().equals(sessionId)) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "request_id 已用于其他会话");
        }
        AnswerEvaluation evaluation = answerEvaluationMapper.selectOne(
                new LambdaQueryWrapper<AnswerEvaluation>()
                        .eq(AnswerEvaluation::getAnswerId, existing.getId()));
        if (evaluation == null) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "幂等记录不完整，请重新提交");
        }
        InterviewQuestion next = evaluation.getSelectedNextQuestionId() == null ? null
                : questionMapper.selectById(evaluation.getSelectedNextQuestionId());
        // 会话状态按该次提交的决策推导：FINISH → REPORTING，否则 IN_PROGRESS
        String status = StagePolicyEngine.ACTION_FINISH.equals(evaluation.getNextAction())
                ? InterviewSessionStatus.REPORTING.getCode()
                : InterviewSessionStatus.IN_PROGRESS.getCode();
        return buildResult(existing.getId(), evaluation, next, status);
    }

    private InterviewQuestion requireCurrentQuestion(InterviewSession session, String questionId) {
        Long submittedId = parseQuestionId(questionId);
        if (session.getCurrentQuestionId() == null
                || !session.getCurrentQuestionId().equals(submittedId)) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "提交的题目不是当前题目（可能已过期），请刷新后重试", HttpStatus.CONFLICT);
        }
        InterviewQuestion current = questionMapper.selectById(submittedId);
        if (current == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "题目不存在，请刷新后重试");
        }
        return current;
    }

    private AiEvaluateAnswerRequest buildEvaluateRequest(
            InterviewSession session, InterviewQuestion current, SubmitAnswerRequest request) {
        AiEvaluateAnswerRequest req = new AiEvaluateAnswerRequest();
        req.setSessionId(String.valueOf(session.getId()));
        req.setQuestionId(String.valueOf(current.getId()));
        req.setAnswerText(request.getAnswerText());
        req.setRoleDirection(session.getRoleDirection());
        req.setQuestionText(current.getQuestionText());
        req.setExpectedPoints(parseExpectedPoints(current.getExpectedPointsJson()));
        req.setStagePlan(parseJson(session.getStagePlanJson()));
        AiEvaluateAnswerRequest.SessionContext context = new AiEvaluateAnswerRequest.SessionContext();
        context.setCurrentStage(session.getCurrentStage());
        context.setCurrentTopic(session.getCurrentTopic());
        context.setQuestionCount(session.getQuestionCount());
        context.setStageCoverage(parseJson(session.getStageCoverageJson()));
        req.setSessionContext(context);
        req.setTraceId(TraceIdContext.currentTraceId());
        return req;
    }

    /**
     * 组装候选池生成的评估事实（供 Redis 缺失重建时再生成追问池）。
     */
    private AiGenerateCandidatePoolRequest.EvaluationFacts toFacts(
            InterviewSession session, InterviewQuestion current, SubmitAnswerRequest request,
            AiEvaluateAnswerResponse eval) {
        AiGenerateCandidatePoolRequest.EvaluationFacts facts =
                new AiGenerateCandidatePoolRequest.EvaluationFacts();
        facts.setScore(eval.getScore());
        facts.setLevel(eval.getLevel());
        facts.setMatchedPoints(eval.getMatchedPoints());
        facts.setMissingPoints(eval.getMissingPoints());
        facts.setRiskPoints(eval.getRiskPoints());
        facts.setAnswerText(request.getAnswerText());
        facts.setQuestionText(current.getQuestionText());
        return facts;
    }

    /**
     * 计算连续弱答计数（含当前题）：得分<30 且无命中要点，向前回溯历史评估。
     */
    private int computeConsecutiveWeak(Long sessionId, AiEvaluateAnswerResponse eval) {
        boolean currentWeak = eval.getScore() != null && eval.getScore() < 30
                && (eval.getMatchedPoints() == null || eval.getMatchedPoints().isEmpty());
        if (!currentWeak) {
            return 0;
        }
        int count = 1;
        List<AnswerEvaluation> recent = answerEvaluationMapper.selectList(
                new LambdaQueryWrapper<AnswerEvaluation>()
                        .eq(AnswerEvaluation::getSessionId, sessionId)
                        .orderByDesc(AnswerEvaluation::getId)
                        .last("LIMIT 20"));
        for (AnswerEvaluation evaluation : recent) {
            boolean weak = evaluation.getScore() != null && evaluation.getScore() < 30
                    && isEmptyJsonArray(evaluation.getMatchedPointsJson());
            if (weak) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private StagePolicyEngine.DecisionInput buildDecisionInput(
            InterviewSession session, InterviewQuestion current, AiEvaluateAnswerResponse eval,
            List<CandidatePoolItem> pool, int consecutiveWeak) {
        StagePolicyEngine.DecisionInput input = new StagePolicyEngine.DecisionInput();
        input.setStagePlanJson(session.getStagePlanJson());
        input.setStageCoverageJson(session.getStageCoverageJson());
        input.setCurrentStage(session.getCurrentStage());
        input.setCurrentTopic(session.getCurrentTopic());
        input.setQuestionCount(session.getQuestionCount());
        input.setScore(eval.getScore() == null ? 0 : eval.getScore());
        input.setMatchedPoints(eval.getMatchedPoints() == null ? new ArrayList<>() : eval.getMatchedPoints());
        input.setConsecutiveWeakCount(consecutiveWeak);
        input.setPool(pool == null ? List.of() : pool);
        // 被答题信息：引擎据此用"本题提交后"的有效覆盖度判断推进/上限（避免每阶段多问 1 题）
        input.setAnsweredQuestionType(current.getQuestionType());
        input.setAnsweredTopic(current.getTopic());
        return input;
    }

    private SubmitAnswerData buildResult(Long answerId,
            AnswerEvaluation evaluation, InterviewQuestion next, String status) {
        var dto = new com.smartview.generated.web.model.AnswerEvaluation(
                evaluation.getScore(), safeLevel(evaluation.getLevel()))
                .id(String.valueOf(evaluation.getId()))
                .evaluationText(evaluation.getEvaluationText());
        return new SubmitAnswerData(String.valueOf(answerId), dto)
                .nextQuestion(next == null ? null : dtoMapper.toQuestion(next))
                .sessionStatus(safeSessionStatus(status));
    }

    private List<CandidatePoolItem> filterItems(List<CandidatePoolItem> items) {
        if (items == null) {
            return List.of();
        }
        List<CandidatePoolItem> result = new ArrayList<>();
        for (CandidatePoolItem item : items) {
            if (item != null && item.getQuestionText() != null && !item.getQuestionText().isBlank()) {
                result.add(item);
            }
        }
        return result;
    }

    private Long parseQuestionId(String questionId) {
        try {
            return Long.parseLong(questionId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "问题 ID 格式非法");
        }
    }

    private List<String> parseExpectedPoints(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode node = parseJson(json);
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
        }
        return result;
    }

    private boolean isEmptyJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return true;
        }
        JsonNode node = parseJson(json);
        return !node.isArray() || node.isEmpty();
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            log.warn("JSON 解析失败，按空处理，error={}", exception.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private com.smartview.generated.web.model.AnswerEvaluation.LevelEnum safeLevel(String level) {
        if (level == null) {
            return null;
        }
        try {
            return com.smartview.generated.web.model.AnswerEvaluation.LevelEnum.fromValue(level);
        } catch (IllegalArgumentException exception) {
            log.warn("评估等级值未知，响应缺省，level={}", level);
            return null;
        }
    }

    private SubmitAnswerData.SessionStatusEnum safeSessionStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return SubmitAnswerData.SessionStatusEnum.fromValue(status);
        } catch (IllegalArgumentException exception) {
            log.warn("会话状态值未知，响应缺省，status={}", status);
            return null;
        }
    }
}
