package com.smartview.interview.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.ai.client.AiFirstQuestionResponse;
import com.smartview.ai.client.AiInterviewClient;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.RoleDirection;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.CreateInterviewSessionRequest;
import com.smartview.interview.dto.InterviewSessionDtoMapper;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.enums.InterviewQuestionStatus;
import com.smartview.interview.enums.InterviewQuestionType;
import com.smartview.interview.enums.InterviewSessionStatus;
import com.smartview.interview.enums.InterviewStage;
import com.smartview.interview.enums.QuestionSourceType;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 面试会话服务。
 *
 * 功能说明：
 * - createSession：创建面试会话（校验确认简历与方向画像分析 → 生成阶段计划 →
 *   落库会话 → 同步调用 FastAPI 生成首题 → 落库首题 → 更新 current_question_id）
 * - getSession：查询会话详情，供页面刷新后恢复当前题目
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

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final ProfileAnalysisMapper profileAnalysisMapper;
    private final StagePlanBuilder stagePlanBuilder;
    private final AiInterviewClient aiInterviewClient;
    private final InterviewSessionDtoMapper dtoMapper;
    private final ObjectMapper objectMapper;

    public InterviewSessionService(
            InterviewSessionMapper sessionMapper,
            InterviewQuestionMapper questionMapper,
            ResumeProfileMapper resumeProfileMapper,
            ProfileAnalysisMapper profileAnalysisMapper,
            StagePlanBuilder stagePlanBuilder,
            AiInterviewClient aiInterviewClient,
            InterviewSessionDtoMapper dtoMapper,
            ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.resumeProfileMapper = resumeProfileMapper;
        this.profileAnalysisMapper = profileAnalysisMapper;
        this.stagePlanBuilder = stagePlanBuilder;
        this.aiInterviewClient = aiInterviewClient;
        this.dtoMapper = dtoMapper;
        this.objectMapper = objectMapper;
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

        return dtoMapper.toResponse(session, question);
    }

    /**
     * 查询会话详情，页面刷新后据此恢复当前题目。
     *
     * @param userId    当前登录用户 ID
     * @param sessionId 会话 ID
     * @return 会话数据模型（含 currentQuestion，无题目时为空）
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
        InterviewQuestion current = session.getCurrentQuestionId() == null
                ? null
                : questionMapper.selectById(session.getCurrentQuestionId());
        return dtoMapper.toResponse(session, current);
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
