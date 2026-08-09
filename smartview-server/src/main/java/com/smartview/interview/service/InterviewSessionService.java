package com.smartview.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.ai.client.AiFirstQuestionResponse;
import com.smartview.ai.client.AiInterviewClient;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.RoleDirection;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.AnswerHistoryItem;
import com.smartview.generated.web.model.CreateInterviewSessionRequest;
import com.smartview.interview.dto.InterviewSessionDtoMapper;
import com.smartview.interview.entity.InterviewAnswer;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.enums.InterviewQuestionStatus;
import com.smartview.interview.enums.InterviewQuestionType;
import com.smartview.interview.enums.InterviewSessionStatus;
import com.smartview.interview.enums.InterviewStage;
import com.smartview.interview.enums.QuestionSourceType;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewAnswerMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.interview.stage.StagePlanBuilder;
import com.smartview.profile.entity.ProfileAnalysis;
import com.smartview.profile.mapper.ProfileAnalysisMapper;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试会话服务。
 *
 * 功能说明：
 * - createSession：创建面试会话（校验确认简历与方向画像分析 → 生成阶段计划 →
 *   落库会话 → 同步调用 FastAPI 生成首题 → 落库首题 → 更新 current_question_id）
 * - getSession：查询会话详情，供页面刷新后恢复当前题目与已回答历史
 * - finishSession：提前结束面试（仅 IN_PROGRESS 可转为 COMPLETED，记录 USER_FINISHED_EARLY）
 *
 * 关键设计：
 * 1. 会话创建在单个事务中完成，FastAPI 首题生成失败时整体回滚，
 *    不留下无首题的孤儿会话；HTTP 调用有独立超时（AiServiceConfig），
 *    事务不会无限挂起；
 * 2. 校验顺序严格前置：先校验简历已确认、再校验该方向画像分析已成功，
 *    未满足任一条件都不能开始面试（验收要求）；
 * 3. 阶段计划由 Spring 确定性生成并落库 stage_plan_json，FastAPI 只负责
 *    基于计划的必覆盖主题生成首题（职责边界见 docs/interview-policy.md）；
 * 4. 会话创建后置 IN_PROGRESS 并记录 started_at、graph_thread_id，
 *    页面可通过 GET 详情按 current_question_id 恢复当前题目。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Slf4j
@Service
public class InterviewSessionService {

    /** 用户主动提前结束的结束原因（interview_session.end_reason，区别于 CANCELLED=放弃不生成报告） */
    private static final String END_REASON_USER_FINISHED_EARLY = "USER_FINISHED_EARLY";

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final ProfileAnalysisMapper profileAnalysisMapper;
    private final StagePlanBuilder stagePlanBuilder;
    private final AiInterviewClient aiInterviewClient;
    private final InterviewSessionDtoMapper dtoMapper;
    private final ObjectMapper objectMapper;
    private final FollowUpPoolService followUpPoolService;
    private final InterviewAnswerMapper answerMapper;
    private final AnswerEvaluationMapper evaluationMapper;

    public InterviewSessionService(
            InterviewSessionMapper sessionMapper,
            InterviewQuestionMapper questionMapper,
            ResumeProfileMapper resumeProfileMapper,
            ProfileAnalysisMapper profileAnalysisMapper,
            StagePlanBuilder stagePlanBuilder,
            AiInterviewClient aiInterviewClient,
            InterviewSessionDtoMapper dtoMapper,
            ObjectMapper objectMapper,
            FollowUpPoolService followUpPoolService,
            InterviewAnswerMapper answerMapper,
            AnswerEvaluationMapper evaluationMapper) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.resumeProfileMapper = resumeProfileMapper;
        this.profileAnalysisMapper = profileAnalysisMapper;
        this.stagePlanBuilder = stagePlanBuilder;
        this.aiInterviewClient = aiInterviewClient;
        this.dtoMapper = dtoMapper;
        this.objectMapper = objectMapper;
        this.followUpPoolService = followUpPoolService;
        this.answerMapper = answerMapper;
        this.evaluationMapper = evaluationMapper;
    }

    /**
     * 创建面试会话。
     *
     * 前置条件（任一不满足则拒绝创建）：
     * - 简历画像已确认（CONFIRMED）
     * - 该方向画像分析已成功生成（profile_analysis 存在且版本匹配）
     *
     * @param userId   当前登录用户 ID
     * @param request  创建会话请求（画像 ID + 面试方向）
     * @return 会话数据模型，含首题与进度范围（expectedMin/MaxQuestions）
     */
    @Transactional(rollbackFor = Exception.class)
    public com.smartview.generated.web.model.InterviewSession createSession(
            Long userId, CreateInterviewSessionRequest request) {
        RoleDirection direction = validateDirection(request);
        ResumeProfile profile = getOwnedProfile(parseProfileId(request.getResumeProfileId()), userId);
        requireConfirmed(profile);
        ProfileAnalysis analysis = requireAnalysisReady(profile, direction.getCode());

        // 基于画像分析确定性生成三阶段计划并落库；FastAPI 据此出题。
        StagePlanBuilder.StagePlan stagePlan = stagePlanBuilder.build(analysis, direction.getCode());
        String stagePlanJson = stagePlan.toJson(objectMapper);

        InterviewSession session = InterviewSession.builder()
                .userId(userId)
                .resumeProfileId(profile.getId())
                .profileAnalysisId(analysis.getId())
                .roleDirection(direction.getCode())
                .status(InterviewSessionStatus.CREATED.getCode())
                .currentStage(InterviewStage.BASIC.getCode())
                .questionCount(0)
                .expectedMinQuestions(stagePlan.getTotalMinQuestions())
                .expectedMaxQuestions(stagePlan.getTotalMaxQuestions())
                .stagePlanJson(stagePlanJson)
                .graphThreadId(UUID.randomUUID().toString())
                .build();
        sessionMapper.insert(session);
        log.info("面试会话已创建，sessionId={}, userId={}, direction={}, analysisId={}",
                session.getId(), userId, direction.getCode(), analysis.getId());

        // 同步调用 FastAPI 生成首题；失败抛出异常触发事务回滚。
        AiFirstQuestionResponse first = aiInterviewClient.generateFirstQuestion(
                session.getId(),
                direction.getCode(),
                stagePlanJson,
                profile.getId(),
                profile.getVersion(),
                TraceIdContext.currentTraceId());
        if (!Boolean.TRUE.equals(first.getSuccess())) {
            String reason = first.getErrorMessage() == null ? "未知错误" : first.getErrorMessage();
            throw new BusinessException(ResponseCode.INTERNAL_ERROR, "首题生成失败：" + reason);
        }

        InterviewQuestion question = buildFirstQuestion(session, first);
        questionMapper.insert(question);

        // 写入首题后回填会话：指向当前题、进入面试中状态。
        LocalDateTime now = LocalDateTime.now();
        session.setCurrentQuestionId(question.getId());
        session.setQuestionCount(1);
        session.setStatus(InterviewSessionStatus.IN_PROGRESS.getCode());
        session.setStartedAt(now);
        sessionMapper.updateById(session);

        // 首题落库并更新会话后，事务提交时异步触发候选池预生成（跨 Bean 调用使 @Async 生效）。
        // 候选池是尽力而为的缓存，生成失败不影响会话创建。
        triggerPreGenerationAfterCommit(session.getId(), question.getId());

        // 新会话尚无历史回答：answers 显式初始化为空数组（契约字段缺省为空列表，避免前端判空差异）
        return dtoMapper.toResponse(session, question).answers(new ArrayList<>());
    }

    /**
     * 事务提交后触发候选池预生成；无活动事务时直接调用（兼容非事务调用方）。
     *
     * 必须等事务提交后再生成：预生成需读取已落库的会话阶段计划/覆盖度，
     * 事务内提交前读取会看到旧数据。
     */
    private void triggerPreGenerationAfterCommit(Long sessionId, Long questionId) {
        Runnable trigger = () -> followUpPoolService.preGenerateAsync(sessionId, questionId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trigger.run();
                }
            });
        } else {
            trigger.run();
        }
    }

    /**
     * 查询会话详情，页面刷新后据此恢复当前题目与已回答历史。
     *
     * @param userId    当前登录用户 ID
     * @param sessionId 会话 ID
     * @return 会话数据模型（含 currentQuestion 与 answers 已回答历史，无题目/历史时为空）
     */
    @Transactional(readOnly = true)
    public com.smartview.generated.web.model.InterviewSession getSession(Long userId, Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "面试会话不存在");
        }
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该面试会话", HttpStatus.FORBIDDEN);
        }
        // 复用统一响应组装：当前问题 + 已回答历史，与提前结束返回保持结构一致
        return buildSessionResponse(session);
    }

    /**
     * 加载会话已回答问题历史（问题 + 回答 + 评估），按提问顺序排序。
     *
     * 供页面刷新后恢复历史问答；仅查 ANSWERED 状态问题（未答/跳过不进入历史）。
     * 单会话问题量有限，采用分批查询在内存按 questionId 关联，避免逐题 N+1。
     */
    private List<AnswerHistoryItem> loadAnswerHistory(Long sessionId) {
        List<InterviewQuestion> answered = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .eq(InterviewQuestion::getStatus, InterviewQuestionStatus.ANSWERED.getCode())
                        .orderByAsc(InterviewQuestion::getQuestionOrder));
        if (answered.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, InterviewAnswer> answerByQuestionId = answerMapper.selectList(
                        new LambdaQueryWrapper<InterviewAnswer>()
                                .eq(InterviewAnswer::getSessionId, sessionId))
                .stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionId, item -> item));
        Map<Long, com.smartview.interview.entity.AnswerEvaluation> evaluationByQuestionId =
                evaluationMapper.selectList(
                                new LambdaQueryWrapper<com.smartview.interview.entity.AnswerEvaluation>()
                                        .eq(com.smartview.interview.entity.AnswerEvaluation::getSessionId, sessionId))
                        .stream()
                        .collect(Collectors.toMap(
                                com.smartview.interview.entity.AnswerEvaluation::getQuestionId, item -> item));
        return answered.stream()
                .map(question -> dtoMapper.toAnswerHistoryItem(
                        question,
                        answerByQuestionId.get(question.getId()),
                        evaluationByQuestionId.get(question.getId())))
                .toList();
    }

    /**
     * 提前结束面试：仅 IN_PROGRESS 会话可转为 COMPLETED，记录 USER_FINISHED_EARLY 结束原因。
     *
     * 使用条件 UPDATE（WHERE id=? AND status='IN_PROGRESS'）天然防并发提交/结束竞态，
     * 命中 0 行说明并发写入者已推进/结束会话，重读后幂等返回现状而非报错。
     * 已处于终态的会话同样幂等返回现状，不重复改写终态。
     */
    @Transactional(rollbackFor = Exception.class)
    public com.smartview.generated.web.model.InterviewSession finishSession(Long userId, Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "面试会话不存在");
        }
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该面试会话", HttpStatus.FORBIDDEN);
        }
        if (!InterviewSessionStatus.IN_PROGRESS.getCode().equals(session.getStatus())) {
            return buildSessionResponse(session);
        }
        sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, InterviewSessionStatus.IN_PROGRESS.getCode())
                .set(InterviewSession::getStatus, InterviewSessionStatus.COMPLETED.getCode())
                .set(InterviewSession::getEndReason, END_REASON_USER_FINISHED_EARLY)
                .set(InterviewSession::getEndedAt, LocalDateTime.now())
                .setSql("version = version + 1"));
        // 条件更新幂等：命中=本次结束成功；未命中=并发写入者已推进/结束。
        // 统一重读最新状态再返回，避免内存态过时，同时保证已终态会话不被改写。
        session = sessionMapper.selectById(sessionId);
        return buildSessionResponse(session);
    }

    /** 组装会话响应：当前问题（可为空）+ 已回答历史 */
    private com.smartview.generated.web.model.InterviewSession buildSessionResponse(InterviewSession session) {
        InterviewQuestion current = session.getCurrentQuestionId() == null
                ? null
                : questionMapper.selectById(session.getCurrentQuestionId());
        return dtoMapper.toResponse(session, current).answers(loadAnswerHistory(session.getId()));
    }

    /**
     * 校验面试方向并解析枚举，非法方向直接参数错误。
     */
    private RoleDirection validateDirection(CreateInterviewSessionRequest request) {
        if (request == null || request.getRoleDirection() == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "面试方向不能为空");
        }
        RoleDirection direction = RoleDirection.fromCode(request.getRoleDirection().getValue());
        if (direction == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "不支持的面试方向");
        }
        return direction;
    }

    /**
     * 查询并校验简历画像归属。
     */
    private ResumeProfile getOwnedProfile(Long profileId, Long userId) {
        ResumeProfile profile = resumeProfileMapper.selectById(profileId);
        if (profile == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "简历画像不存在");
        }
        if (!userId.equals(profile.getUserId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该简历画像", HttpStatus.FORBIDDEN);
        }
        return profile;
    }

    /**
     * 校验简历画像已确认；未确认时禁止开始面试。
     */
    private void requireConfirmed(ResumeProfile profile) {
        if (!ConfirmStatus.CONFIRMED.getCode().equals(profile.getConfirmStatus())) {
            throw new BusinessException(ResponseCode.CONFLICT, "请先确认简历画像，再进行面试", HttpStatus.CONFLICT);
        }
    }

    /**
     * 校验该方向画像分析已成功生成（当前画像版本下）。
     *
     * 画像分析通过 MQ 异步生成；此处直接查询 profile_analysis 表，
     * 存在即代表已成功落库。未生成时应先触发/等待画像分析，而非开始面试。
     */
    private ProfileAnalysis requireAnalysisReady(ResumeProfile profile, String roleDirection) {
        ProfileAnalysis analysis = profileAnalysisMapper.selectOne(
                new LambdaQueryWrapper<ProfileAnalysis>()
                        .eq(ProfileAnalysis::getResumeProfileId, profile.getId())
                        .eq(ProfileAnalysis::getRoleDirection, roleDirection)
                        .eq(ProfileAnalysis::getProfileVersion, profile.getVersion()));
        if (analysis == null) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "该方向画像分析尚未生成，请先完成画像分析后再开始面试", HttpStatus.CONFLICT);
        }
        return analysis;
    }

    /**
     * 组装首题实体：BASIC 阶段开场题，问题序号从 1 开始。
     */
    private InterviewQuestion buildFirstQuestion(
            InterviewSession session, AiFirstQuestionResponse first) {
        LocalDateTime now = LocalDateTime.now();
        return InterviewQuestion.builder()
                .sessionId(session.getId())
                .userId(session.getUserId())
                .questionOrder(1)
                .stage(InterviewStage.BASIC.getCode())
                .questionType(safeQuestionType(first.getQuestionType()))
                .topic(first.getTopic())
                .questionText(first.getQuestionText())
                .sourceType(safeSourceType(first.getSourceType()))
                .knowledgeRefsJson(toJson(first.getKnowledgeRefs()))
                .caseRefsJson(toJson(first.getCaseRefs()))
                .expectedPointsJson(toJson(first.getExpectedPoints()))
                .status(InterviewQuestionStatus.ASKED.getCode())
                .askedAt(now)
                .build();
    }

    private String safeQuestionType(String code) {
        InterviewQuestionType type = InterviewQuestionType.fromCode(code);
        return type == null ? InterviewQuestionType.OPENING.getCode() : type.getCode();
    }

    private String safeSourceType(String code) {
        QuestionSourceType type = QuestionSourceType.fromCode(code);
        // 来源未知时兜底为知识库，保证溯源字段可枚举。
        return type == null ? QuestionSourceType.KNOWLEDGE_BASE.getCode() : type.getCode();
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("首题引用信息序列化失败，该字段留空，error={}", exception.getMessage());
            return null;
        }
    }

    private Long parseProfileId(String profileId) {
        try {
            return Long.parseLong(profileId);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "简历画像 ID 格式非法");
        }
    }
}
