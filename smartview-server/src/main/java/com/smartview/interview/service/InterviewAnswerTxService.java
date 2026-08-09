package com.smartview.interview.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartview.ai.client.AiEvaluateAnswerResponse;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.AnswerEvaluation;
import com.smartview.generated.web.model.SubmitAnswerData;
import com.smartview.generated.web.model.SubmitAnswerRequest;
import com.smartview.interview.dto.InterviewSessionDtoMapper;
import com.smartview.interview.engine.StagePolicyEngine;
import com.smartview.interview.entity.InterviewAnswer;
import com.smartview.interview.enums.InterviewQuestionStatus;
import com.smartview.interview.enums.InterviewQuestionType;
import com.smartview.interview.enums.InterviewSessionStatus;
import com.smartview.interview.enums.QuestionSourceType;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewAnswerMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.interview.model.CandidatePoolItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 回答提交事务落库服务。
 *
 * 职责：在单一事务内完成回答提交的全部写库——回答、当前题置 ANSWERED、评估
 * （含决策快照）、下一题（未结束时）、会话乐观锁推进（结束时置 REPORTING）。
 *
 * 与 InterviewAnswerService 分离的原因：评估调用 FastAPI（HTTP）在事务外执行，
 * 本服务只承载事务边界内的写库，避免长事务占住 DB 连接（设计决策见 Task 5.4）。
 *
 * @author SmartView Team
 * @since 2026-08-09
 */
@Slf4j
@Service
public class InterviewAnswerTxService {

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final AnswerEvaluationMapper answerEvaluationMapper;
    private final InterviewSessionDtoMapper dtoMapper;
    private final ObjectMapper objectMapper;

    public InterviewAnswerTxService(
            InterviewSessionMapper sessionMapper,
            InterviewQuestionMapper questionMapper,
            InterviewAnswerMapper answerMapper,
            AnswerEvaluationMapper answerEvaluationMapper,
            InterviewSessionDtoMapper dtoMapper,
            ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.answerEvaluationMapper = answerEvaluationMapper;
        this.dtoMapper = dtoMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 单事务内完成回答提交的全部落库并返回响应数据。
     *
     * @param session         会话实体（version 为旧值，由乐观锁 SQL 自增）
     * @param currentQuestion 当前被回答的题目
     * @param request         提交请求（含 requestId 幂等键）
     * @param eval            FastAPI 评估事实
     * @param decision        StagePolicyEngine 决策结果
     * @param pool            本次决策使用的候选池（写决策快照）
     * @return 响应数据（answerId/评估/下一题/会话状态）
     */
    @Transactional(rollbackFor = Exception.class)
    public SubmitAnswerData persist(Long userId,
            com.smartview.interview.entity.InterviewSession session,
            com.smartview.interview.entity.InterviewQuestion currentQuestion,
            SubmitAnswerRequest request,
            AiEvaluateAnswerResponse eval,
            StagePolicyEngine.Decision decision,
            List<CandidatePoolItem> pool) {
        // 1. 回答落库
        InterviewAnswer answer = buildAnswer(session, currentQuestion, request);
        answerMapper.insert(answer);

        // 2. 当前题置 ANSWERED（仅更新状态字段）
        com.smartview.interview.entity.InterviewQuestion statusUpdate =
                new com.smartview.interview.entity.InterviewQuestion();
        statusUpdate.setId(currentQuestion.getId());
        statusUpdate.setStatus(InterviewQuestionStatus.ANSWERED.getCode());
        questionMapper.updateById(statusUpdate);

        // 3. 未结束则先插入下一题（评估快照需要 selectedNextQuestionId）
        com.smartview.interview.entity.InterviewQuestion nextQuestion = null;
        if (!StagePolicyEngine.ACTION_FINISH.equals(decision.getNextAction())) {
            nextQuestion = insertNextQuestion(session, currentQuestion, decision);
        }

        // 4. 评估落库（含决策快照与选中下一题）
        com.smartview.interview.entity.AnswerEvaluation evaluation = buildEvaluation(
                session, currentQuestion, answer, eval, decision,
                nextQuestion == null ? null : nextQuestion.getId(), pool);
        answerEvaluationMapper.insert(evaluation);

        // 5. 会话推进/结束（乐观锁）
        applySessionAdvance(session, currentQuestion, decision, nextQuestion);

        return buildResult(answer, evaluation, nextQuestion, session.getStatus());
    }

    /**
     * 会话乐观锁推进：未结束时指向下一题并保持 IN_PROGRESS；
     * 结束时置 REPORTING + end_reason + ended_at（报告生成在 Phase 6 接入）。
     */
    private void applySessionAdvance(com.smartview.interview.entity.InterviewSession session,
            com.smartview.interview.entity.InterviewQuestion answered,
            StagePolicyEngine.Decision decision,
            com.smartview.interview.entity.InterviewQuestion nextQuestion) {
        if (nextQuestion != null) {
            session.setCurrentQuestionId(nextQuestion.getId());
            session.setCurrentTopic(nextQuestion.getTopic());
            session.setCurrentStage(nextQuestion.getStage());
            session.setQuestionCount(session.getQuestionCount() + 1);
            session.setStatus(InterviewSessionStatus.IN_PROGRESS.getCode());
            session.setEndReason(null);
            session.setEndedAt(null);
        } else {
            session.setStatus(InterviewSessionStatus.REPORTING.getCode());
            session.setEndReason(decision.getEndReason());
            session.setEndedAt(LocalDateTime.now());
            // 报告生成任务在 Phase 6（Task 6.1/6.2）接入：本期仅推进状态到 REPORTING
            log.info("面试结束进入报告阶段 sessionId={} endReason={}（报告生成留待 Phase 6）",
                    session.getId(), decision.getEndReason());
        }
        session.setStageCoverageJson(applyCoverage(session, answered, decision));
        int rows = sessionMapper.optimisticAdvance(session);
        if (rows == 0) {
            // 影响行数为 0 说明 version 不匹配：多端/重复点击并发推进，拒绝本次更新
            throw new BusinessException(ResponseCode.CONFLICT, "会话已被其他请求更新，请刷新后重试",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * 依据选中的候选插入下一题：追问指向当前题形成树，换题/阶段入口无父题。
     */
    private com.smartview.interview.entity.InterviewQuestion insertNextQuestion(
            com.smartview.interview.entity.InterviewSession session,
            com.smartview.interview.entity.InterviewQuestion current,
            StagePolicyEngine.Decision decision) {
        CandidatePoolItem candidate = decision.getSelectedCandidate();
        if (candidate == null) {
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "决策未选择下一题，无法推进面试");
        }
        LocalDateTime now = LocalDateTime.now();
        com.smartview.interview.entity.InterviewQuestion next =
                com.smartview.interview.entity.InterviewQuestion.builder()
                        .sessionId(session.getId())
                        .userId(session.getUserId())
                        .questionOrder(session.getQuestionCount() + 1)
                        .parentQuestionId(StagePolicyEngine.ACTION_FOLLOW_UP.equals(decision.getNextAction())
                                ? current.getId() : null)
                        .stage(candidate.getStage())
                        .questionType(mapQuestionType(candidate.getCandidateType()))
                        .topic(candidate.getTopic())
                        .questionText(candidate.getQuestionText())
                        .sourceType(safeSourceType(candidate.getSourceType()))
                        .expectedPointsJson(toJson(candidate.getExpectedPoints()))
                        .status(InterviewQuestionStatus.ASKED.getCode())
                        .askedAt(now)
                        .build();
        questionMapper.insert(next);
        return next;
    }

    private InterviewAnswer buildAnswer(com.smartview.interview.entity.InterviewSession session,
            com.smartview.interview.entity.InterviewQuestion question, SubmitAnswerRequest request) {
        return InterviewAnswer.builder()
                .sessionId(session.getId())
                .questionId(question.getId())
                .userId(session.getUserId())
                .answerText(request.getAnswerText())
                .answerMode("TEXT")
                .durationSeconds(request.getDurationSeconds())
                .requestId(request.getRequestId() == null ? null : request.getRequestId().toString())
                .submittedAt(LocalDateTime.now())
                .build();
    }

    private com.smartview.interview.entity.AnswerEvaluation buildEvaluation(
            com.smartview.interview.entity.InterviewSession session,
            com.smartview.interview.entity.InterviewQuestion question,
            InterviewAnswer answer, AiEvaluateAnswerResponse eval,
            StagePolicyEngine.Decision decision, Long nextQuestionId,
            List<CandidatePoolItem> pool) {
        return com.smartview.interview.entity.AnswerEvaluation.builder()
                .sessionId(session.getId())
                .questionId(question.getId())
                .answerId(answer.getId())
                .score(eval.getScore())
                .level(eval.getLevel())
                .matchedPointsJson(toJson(eval.getMatchedPoints()))
                .missingPointsJson(toJson(eval.getMissingPoints()))
                .riskPointsJson(toJson(eval.getRiskPoints()))
                .nextAction(decision.getNextAction())
                .candidatePoolSnapshotJson(buildSnapshotJson(pool, decision, eval))
                .selectedNextQuestionId(nextQuestionId)
                .evaluationText("得分 " + eval.getScore() + "，等级 " + eval.getLevel())
                .build();
    }

    /**
     * 构建决策快照 JSON（interview-policy.md 9.1）：
     * 顶层 candidates 数组（FollowUpPoolService.readRecentSnapshot 按此读取重建）
     * + decision（动作/目标阶段/选中/排除/原因）+ facts（评估事实）。
     */
    private String buildSnapshotJson(List<CandidatePoolItem> pool,
            StagePolicyEngine.Decision decision, AiEvaluateAnswerResponse eval) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("candidates", objectMapper.valueToTree(pool == null ? List.of() : pool));
        ObjectNode d = objectMapper.createObjectNode();
        d.put("nextAction", decision.getNextAction());
        if (decision.getNextStage() != null) {
            d.put("nextStage", decision.getNextStage());
        }
        if (decision.getEndReason() != null) {
            d.put("endReason", decision.getEndReason());
        }
        if (decision.getSelectedCandidate() != null) {
            d.set("selectedCandidate", objectMapper.valueToTree(decision.getSelectedCandidate()));
        }
        d.put("decisionReason", decision.getDecisionReason());
        d.set("excluded", objectMapper.valueToTree(decision.getExcluded()));
        root.set("decision", d);
        ObjectNode facts = objectMapper.createObjectNode();
        facts.put("score", eval.getScore() == null ? 0 : eval.getScore());
        facts.put("level", eval.getLevel() == null ? "" : eval.getLevel());
        facts.set("matchedPoints", objectMapper.valueToTree(nullToEmpty(eval.getMatchedPoints())));
        facts.set("missingPoints", objectMapper.valueToTree(nullToEmpty(eval.getMissingPoints())));
        facts.set("riskPoints", objectMapper.valueToTree(nullToEmpty(eval.getRiskPoints())));
        root.set("facts", facts);
        return toJson(root);
    }

    /**
     * 覆盖度更新（interview-policy.md 2.3，snake_case）：
     * 本阶段题数 +1、本题主题并入 covered、missing 重算、追问深度更新、下一阶段初始化。
     */
    private String applyCoverage(com.smartview.interview.entity.InterviewSession session,
            com.smartview.interview.entity.InterviewQuestion answered,
            StagePolicyEngine.Decision decision) {
        ObjectNode coverage = parseCoverage(session.getStageCoverageJson());
        JsonNode plan = parseJson(session.getStagePlanJson());
        ObjectNode stageNode = coverageObject(coverage, answered.getStage());

        stageNode.put("question_count", stageNode.path("question_count").asInt(0) + 1);

        ArrayNode covered = asArray(stageNode.get("covered_topics"));
        if (answered.getTopic() != null && !answered.getTopic().isBlank()
                && !containsText(covered, answered.getTopic())) {
            covered.add(answered.getTopic());
        }
        stageNode.set("covered_topics", covered);

        // missing = required - covered
        ArrayNode missing = objectMapper.createArrayNode();
        for (JsonNode topic : requiredTopics(plan, answered.getStage())) {
            if (!containsText(covered, topic.asText())) {
                missing.add(topic.asText());
            }
        }
        stageNode.set("missing_topics", missing);

        // 当前主题连续追问深度：本题为追问则 +1，否则归 0（新主题/新阶段起点）
        boolean isFollowUp = InterviewQuestionType.FOLLOW_UP.getCode().equals(answered.getQuestionType());
        stageNode.put("current_topic_follow_up_count",
                isFollowUp ? stageNode.path("current_topic_follow_up_count").asInt(0) + 1 : 0);

        // NEXT_STAGE：初始化下一阶段覆盖项（未覆盖任何主题）
        if (decision.getNextStage() != null) {
            ObjectNode next = coverageObject(coverage, decision.getNextStage());
            next.put("question_count", 0);
            next.set("covered_topics", objectMapper.createArrayNode());
            next.set("missing_topics", requiredTopics(plan, decision.getNextStage()));
            next.put("current_topic_follow_up_count", 0);
        }
        return toJson(coverage);
    }

    private SubmitAnswerData buildResult(InterviewAnswer answer,
            com.smartview.interview.entity.AnswerEvaluation evaluation,
            com.smartview.interview.entity.InterviewQuestion next, String status) {
        AnswerEvaluation dto = new AnswerEvaluation(evaluation.getScore(), safeLevel(evaluation.getLevel()))
                .id(String.valueOf(evaluation.getId()))
                .evaluationText(evaluation.getEvaluationText());
        return new SubmitAnswerData(String.valueOf(answer.getId()), dto)
                .nextQuestion(next == null ? null : dtoMapper.toQuestion(next))
                .sessionStatus(safeSessionStatus(status));
    }

    // ==================== 私有辅助 ====================

    private String mapQuestionType(String candidateType) {
        if (StagePolicyEngine.ACTION_FOLLOW_UP.equals(candidateType)) {
            return InterviewQuestionType.FOLLOW_UP.getCode();
        }
        if ("SAME_STAGE_SWITCH".equals(candidateType)) {
            return InterviewQuestionType.SWITCH_TOPIC.getCode();
        }
        return InterviewQuestionType.STAGE_ENTRY.getCode();
    }

    private String safeSourceType(String sourceType) {
        QuestionSourceType type = QuestionSourceType.fromCode(sourceType);
        // 来源未知时兜底为知识库，保证溯源字段可枚举
        return type == null ? QuestionSourceType.KNOWLEDGE_BASE.getCode() : type.getCode();
    }

    private AnswerEvaluation.LevelEnum safeLevel(String level) {
        if (level == null) {
            return null;
        }
        try {
            return AnswerEvaluation.LevelEnum.fromValue(level);
        } catch (IllegalArgumentException exception) {
            // 未知等级值：响应缺省该字段而非整体 500（与 dtoMapper 安全转换一致）
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

    private ObjectNode parseCoverage(String json) {
        JsonNode node = parseJson(json);
        return node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
    }

    private JsonNode parseJson(String json) {
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

    private ObjectNode coverageObject(ObjectNode coverage, String stage) {
        JsonNode node = coverage.get(stage);
        if (node == null || !node.isObject()) {
            node = objectMapper.createObjectNode();
            coverage.set(stage, node);
        }
        return (ObjectNode) node;
    }

    private ArrayNode asArray(JsonNode node) {
        if (node != null && node.isArray()) {
            return (ArrayNode) node;
        }
        return objectMapper.createArrayNode();
    }

    private boolean containsText(ArrayNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode requiredTopics(JsonNode plan, String stage) {
        ArrayNode result = objectMapper.createArrayNode();
        for (JsonNode item : plan.path("stages")) {
            if (stage.equals(item.path("stage").asText())) {
                item.path("required_topics").forEach(result::add);
                break;
            }
        }
        return result;
    }

    private <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("JSON 序列化失败，该字段留空，error={}", exception.getMessage());
            return null;
        }
    }
}
