package com.smartview.interview.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.ai.client.AiFirstQuestionResponse;
import com.smartview.ai.client.AiInterviewClient;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.RoleDirection;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.CreateInterviewSessionRequest;
import com.smartview.interview.dto.InterviewSessionDtoMapper;
import com.smartview.interview.entity.InterviewAnswer;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewAnswerMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.interview.stage.StagePlanBuilder;
import com.smartview.profile.entity.ProfileAnalysis;
import com.smartview.profile.mapper.ProfileAnalysisMapper;
import com.smartview.report.service.ReportTaskService;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeProfileMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试会话服务测试。
 *
 * 覆盖验收标准：
 * 1. 没有确认简历时不能开始面试（抛 CONFLICT）
 * 2. 没有该方向画像分析时不能开始面试（抛 CONFLICT，应先触发/等待画像分析）
 * 3. 创建成功返回首题和进度范围（currentQuestion + expectedMin/Max）
 * 4. 首题来源能记录为知识库/面经/简历项目/混合来源（sourceType 与引用落库）
 * 5. FastAPI 首题失败时整体回滚（不落库会话/问题）
 * 6. 会话详情查询的归属校验与当前问题恢复
 */
@ExtendWith(MockitoExtension.class)
class InterviewSessionServiceTest {

    @Mock
    private InterviewSessionMapper sessionMapper;
    @Mock
    private InterviewQuestionMapper questionMapper;
    @Mock
    private ResumeProfileMapper resumeProfileMapper;
    @Mock
    private ProfileAnalysisMapper profileAnalysisMapper;
    @Mock
    private AiInterviewClient aiInterviewClient;
    @Mock
    private FollowUpPoolService followUpPoolService;
    @Mock
    private InterviewAnswerMapper answerMapper;
    @Mock
    private AnswerEvaluationMapper evaluationMapper;
    @Mock
    private ReportTaskService reportTaskService;

    private InterviewSessionService service;
    private ObjectMapper objectMapper;

    /** 记录 insert 时会话的初始状态（对象随后被 service 更新，captor 引用共享无法回看）。 */
    private String insertedSessionStatus;

    /**
     * 初始化 InterviewSession 的 MyBatis-Plus 表元数据（Lambda 缓存）。
     *
     * 纯 Mockito 单测没有 MyBatis-Plus 启动流程，LambdaUpdateWrapper 的列名解析
     * 依赖 TableInfoHelper 构建的缓存（getSqlSet/getSqlSegment 触发），
     * 此处手动初始化以便断言条件更新的 WHERE/SET 片段。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                InterviewSession.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        insertedSessionStatus = null;
        service = new InterviewSessionService(
                sessionMapper,
                questionMapper,
                resumeProfileMapper,
                profileAnalysisMapper,
                new StagePlanBuilder(objectMapper),
                aiInterviewClient,
                new InterviewSessionDtoMapper(),
                objectMapper,
                followUpPoolService,
                answerMapper,
                evaluationMapper,
                reportTaskService);
    }

    private ResumeProfile confirmedProfile() {
        return ResumeProfile.builder()
                .id(10L)
                .userId(7L)
                .resumeFileId(3L)
                .version(1)
                .confirmStatus(ConfirmStatus.CONFIRMED.getCode())
                .build();
    }

    private ProfileAnalysis analysis() {
        return ProfileAnalysis.builder()
                .id(100L)
                .userId(7L)
                .resumeProfileId(10L)
                .roleDirection(RoleDirection.JAVA_BACKEND.getCode())
                .profileVersion(1)
                .stageTargetsJson("{\"basic\":[\"Java 并发\"],\"project\":[],\"scenario\":[\"系统设计\"]}")
                .suggestedTopicsJson("[\"Java 并发\",\"JVM\"]")
                .projectGraphJson("{\"projects\":[{\"projectName\":\"电商平台\"}]}")
                .build();
    }

    private CreateInterviewSessionRequest request() {
        CreateInterviewSessionRequest request = new CreateInterviewSessionRequest();
        request.setResumeProfileId("10");
        request.setRoleDirection(CreateInterviewSessionRequest.RoleDirectionEnum.JAVA_BACKEND);
        return request;
    }

    private AiFirstQuestionResponse firstQuestionResponse(String sourceType) {
        AiFirstQuestionResponse response = new AiFirstQuestionResponse();
        response.setSuccess(true);
        response.setQuestionText("请解释 Java 内存模型中的 happens-before 原则。");
        response.setTopic("Java 并发");
        response.setQuestionType("OPENING");
        response.setSourceType(sourceType);
        response.setExpectedPoints(List.of("能说出定义", "能举例说明"));
        return response;
    }

    /** mock MyBatis-Plus 插入回填自增主键，还原真实 insert 行为。 */
    private void mockInsertBackfill() {
        doAnswer(invocation -> {
            InterviewSession session = invocation.getArgument(0);
            insertedSessionStatus = session.getStatus();
            session.setId(1L);
            return 1;
        }).when(sessionMapper).insert(any(InterviewSession.class));
        doAnswer(invocation -> {
            InterviewQuestion question = invocation.getArgument(0);
            question.setId(11L);
            return 1;
        }).when(questionMapper).insert(any(InterviewQuestion.class));
    }

    @Test
    void createSession_未确认简历时禁止开始() {
        ResumeProfile unconfirmed = ResumeProfile.builder()
                .id(10L)
                .userId(7L)
                .version(1)
                .confirmStatus(ConfirmStatus.UNCONFIRMED.getCode())
                .build();
        when(resumeProfileMapper.selectById(10L)).thenReturn(unconfirmed);

        assertThatThrownBy(() -> service.createSession(7L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先确认简历画像");
        // 校验未通过，不得继续创建会话或调用 AI 服务
        verify(sessionMapper, never()).insert(any(InterviewSession.class));
        verify(aiInterviewClient, never()).generateFirstQuestion(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void createSession_无权访问他人简历时禁止() {
        when(resumeProfileMapper.selectById(10L)).thenReturn(confirmedProfile());

        assertThatThrownBy(() -> service.createSession(99L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该简历画像");
        verify(sessionMapper, never()).insert(any(InterviewSession.class));
    }

    @Test
    void createSession_无该方向画像分析时禁止开始() {
        when(resumeProfileMapper.selectById(10L)).thenReturn(confirmedProfile());
        // 该方向画像分析缺失
        when(profileAnalysisMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.createSession(7L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("画像分析尚未生成");
        verify(sessionMapper, never()).insert(any(InterviewSession.class));
        verify(aiInterviewClient, never()).generateFirstQuestion(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void createSession_画像分析版本不匹配时禁止开始() {
        ResumeProfile profile = confirmedProfile();
        // 画像当前版本为 1，但已有分析属于旧版本 0
        when(resumeProfileMapper.selectById(10L)).thenReturn(profile);
        when(profileAnalysisMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.createSession(7L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("画像分析尚未生成");
    }

    @Test
    void createSession_成功返回首题和进度范围() {
        when(resumeProfileMapper.selectById(10L)).thenReturn(confirmedProfile());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(analysis());
        when(aiInterviewClient.generateFirstQuestion(
                eq(1L), eq(RoleDirection.JAVA_BACKEND.getCode()), any(), eq(10L), eq(1), any()))
                .thenReturn(firstQuestionResponse("KNOWLEDGE_BASE"));
        mockInsertBackfill();

        com.smartview.generated.web.model.InterviewSession response = service.createSession(7L, request());

        // 会话落库：先 CREATED，写入首题后 IN_PROGRESS 且回填 current_question_id
        ArgumentCaptor<InterviewSession> insertCaptor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(sessionMapper).insert(insertCaptor.capture());
        assertThat(insertedSessionStatus).isEqualTo("CREATED");
        assertThat(insertCaptor.getValue().getCurrentStage()).isEqualTo("BASIC");
        assertThat(insertCaptor.getValue().getExpectedMinQuestions()).isEqualTo(7);
        assertThat(insertCaptor.getValue().getExpectedMaxQuestions()).isEqualTo(20);
        assertThat(insertCaptor.getValue().getStagePlanJson()).contains("\"BASIC\"").contains("required_topics");

        ArgumentCaptor<InterviewSession> updateCaptor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(sessionMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(updateCaptor.getValue().getCurrentQuestionId()).isEqualTo(11L);
        assertThat(updateCaptor.getValue().getQuestionCount()).isEqualTo(1);

        // 首题落库：序号 1、BASIC 开场题、来源为知识库
        ArgumentCaptor<InterviewQuestion> questionCaptor = ArgumentCaptor.forClass(InterviewQuestion.class);
        verify(questionMapper).insert(questionCaptor.capture());
        InterviewQuestion question = questionCaptor.getValue();
        assertThat(question.getQuestionOrder()).isEqualTo(1);
        assertThat(question.getStage()).isEqualTo("BASIC");
        assertThat(question.getQuestionType()).isEqualTo("OPENING");
        assertThat(question.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
        assertThat(question.getStatus()).isEqualTo("ASKED");

        // 响应含首题与进度范围
        assertThat(response.getCurrentQuestion().getQuestionText())
                .isEqualTo("请解释 Java 内存模型中的 happens-before 原则。");
        assertThat(response.getCurrentQuestion().getSourceType())
                .isEqualTo(com.smartview.generated.web.model.InterviewQuestion.SourceTypeEnum.KNOWLEDGE_BASE);
        assertThat(response.getExpectedMinQuestions()).isEqualTo(7);
        assertThat(response.getExpectedMaxQuestions()).isEqualTo(20);
        // 新会话尚无历史：answers 必须为空数组而非 null
        assertThat(response.getAnswers()).isEmpty();
        assertThat(response.getStatus())
                .isEqualTo(com.smartview.generated.web.model.InterviewSession.StatusEnum.IN_PROGRESS);

        // 会话创建成功且首题落库后，异步触发候选池预生成
        verify(followUpPoolService).preGenerateAsync(1L, 11L);
    }

    @Test
    void createSession_首题来源可记录为混合来源() {
        AiFirstQuestionResponse mixed = firstQuestionResponse("MIXED");
        mixed.setKnowledgeRefs(List.of(new AiFirstQuestionResponse.KnowledgeRef() {{
            setTitle("并发模型");
            setCategory("并发");
            setSnippet("happens-before 规则…");
        }}));
        mixed.setCaseRefs(List.of(new AiFirstQuestionResponse.CaseRef() {{
            setTitle("面经：并发追问");
            setScenario("电商并发场景");
            setSnippet("面试官追问…");
        }}));

        when(resumeProfileMapper.selectById(10L)).thenReturn(confirmedProfile());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(analysis());
        when(aiInterviewClient.generateFirstQuestion(
                eq(1L), eq(RoleDirection.JAVA_BACKEND.getCode()), any(), eq(10L), eq(1), any()))
                .thenReturn(mixed);
        mockInsertBackfill();

        service.createSession(7L, request());

        ArgumentCaptor<InterviewQuestion> questionCaptor = ArgumentCaptor.forClass(InterviewQuestion.class);
        verify(questionMapper).insert(questionCaptor.capture());
        InterviewQuestion question = questionCaptor.getValue();
        assertThat(question.getSourceType()).isEqualTo("MIXED");
        // 引用信息以 JSON 落库，供溯源与复盘
        assertThat(question.getKnowledgeRefsJson()).contains("并发模型").contains("happens-before");
        assertThat(question.getCaseRefsJson()).contains("面经：并发追问");
        assertThat(question.getExpectedPointsJson()).contains("能说出定义");
    }

    @Test
    void createSession_FastAPI首题失败时整体回滚() {
        when(resumeProfileMapper.selectById(10L)).thenReturn(confirmedProfile());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(analysis());
        AiFirstQuestionResponse failed = new AiFirstQuestionResponse();
        failed.setSuccess(false);
        failed.setErrorMessage("AI 服务暂不可用");
        when(aiInterviewClient.generateFirstQuestion(any(), any(), any(), any(), any(), any()))
                .thenReturn(failed);

        assertThatThrownBy(() -> service.createSession(7L, request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("首题生成失败");
        // 首题失败时不得写入问题、不得将会话推进为进行中
        verify(questionMapper, never()).insert(any(InterviewQuestion.class));
        verify(sessionMapper, never()).updateById(any(InterviewSession.class));
    }

    @Test
    void createSession_FastAPI调用异常时整体回滚() {
        when(resumeProfileMapper.selectById(10L)).thenReturn(confirmedProfile());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(analysis());
        when(aiInterviewClient.generateFirstQuestion(any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(com.smartview.common.api.ResponseCode.INTERNAL_ERROR, "AI 服务暂不可用"));

        assertThatThrownBy(() -> service.createSession(7L, request()))
                .isInstanceOf(BusinessException.class);
        verify(questionMapper, never()).insert(any(InterviewQuestion.class));
        verify(sessionMapper, never()).updateById(any(InterviewSession.class));
    }

    @Test
    void getSession_返回已回答问题历史并按提问顺序排序() {
        InterviewSession session = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("IN_PROGRESS").currentQuestionId(22L)
                .questionCount(2).expectedMinQuestions(5).expectedMaxQuestions(8).build();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(questionMapper.selectById(22L))
                .thenReturn(InterviewQuestion.builder().id(22L).sessionId(1L).questionText("当前题").build());

        // mock 故意以乱序（问题二在前）返回，验证服务端在内存中重新按提问序号排序，
        // 而非仅依赖 mock/数据库返回顺序
        when(questionMapper.selectList(any())).thenReturn(List.of(
                InterviewQuestion.builder().id(12L).sessionId(1L).questionOrder(2)
                        .questionText("问题二").status("ANSWERED").build(),
                InterviewQuestion.builder().id(11L).sessionId(1L).questionOrder(1)
                        .questionText("问题一").status("ANSWERED").build()));
        when(answerMapper.selectList(any())).thenReturn(List.of(
                InterviewAnswer.builder().id(101L).questionId(11L).answerText("回答一")
                        .submittedAt(LocalDateTime.of(2026, 8, 9, 10, 0)).build(),
                InterviewAnswer.builder().id(102L).questionId(12L).answerText("回答二")
                        .submittedAt(LocalDateTime.of(2026, 8, 9, 10, 1)).build()));
        when(evaluationMapper.selectList(any())).thenReturn(List.of(
                com.smartview.interview.entity.AnswerEvaluation.builder()
                        .id(201L).questionId(11L).score(85).level("GOOD")
                        .evaluationText("要点清晰").build()));

        com.smartview.generated.web.model.InterviewSession response = service.getSession(7L, 1L);

        assertThat(response.getAnswers()).hasSize(2);
        assertThat(response.getAnswers().get(0).getQuestion().getQuestionText()).isEqualTo("问题一");
        assertThat(response.getAnswers().get(0).getAnswerText()).isEqualTo("回答一");
        assertThat(response.getAnswers().get(0).getEvaluation().getScore()).isEqualTo(85);
        assertThat(response.getAnswers().get(0).getEvaluation().getLevel())
                .isEqualTo(com.smartview.generated.web.model.AnswerEvaluation.LevelEnum.GOOD);
        assertThat(response.getAnswers().get(0).getEvaluation().getEvaluationText()).isEqualTo("要点清晰");
        // 第二个问题无评估：evaluation 缺省为 null 而非报错
        assertThat(response.getAnswers().get(1).getQuestion().getQuestionText()).isEqualTo("问题二");
        assertThat(response.getAnswers().get(1).getEvaluation()).isNull();
        // 当前题目仍正确恢复
        assertThat(response.getCurrentQuestion().getQuestionText()).isEqualTo("当前题");
    }

    @Test
    void getSession_无已回答问题时历史为空数组() {
        InterviewSession session = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("IN_PROGRESS").currentQuestionId(22L).build();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(questionMapper.selectById(22L))
                .thenReturn(InterviewQuestion.builder().id(22L).sessionId(1L).questionText("当前题").build());
        when(questionMapper.selectList(any())).thenReturn(List.of());

        com.smartview.generated.web.model.InterviewSession response = service.getSession(7L, 1L);

        assertThat(response.getAnswers()).isEmpty();
    }

    @Test
    void getSession_返回会话与当前问题() {
        InterviewSession session = InterviewSession.builder()
                .id(1L)
                .userId(7L)
                .resumeProfileId(10L)
                .roleDirection(RoleDirection.JAVA_BACKEND.getCode())
                .status("IN_PROGRESS")
                .currentQuestionId(11L)
                .questionCount(1)
                .build();
        InterviewQuestion question = InterviewQuestion.builder()
                .id(11L)
                .sessionId(1L)
                .userId(7L)
                .questionOrder(1)
                .questionText("请解释 happens-before 原则。")
                .build();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        when(questionMapper.selectById(11L)).thenReturn(question);

        com.smartview.generated.web.model.InterviewSession response = service.getSession(7L, 1L);

        assertThat(response.getId()).isEqualTo("1");
        assertThat(response.getCurrentQuestion().getQuestionText())
                .isEqualTo("请解释 happens-before 原则。");
    }

    @Test
    void getSession_会话不存在时抛404() {
        when(sessionMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.getSession(7L, 999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("会话不存在");
    }

    @Test
    void getSession_无权访问他人会话时抛403() {
        InterviewSession session = InterviewSession.builder()
                .id(1L)
                .userId(7L)
                .resumeProfileId(10L)
                .roleDirection(RoleDirection.JAVA_BACKEND.getCode())
                .status("IN_PROGRESS")
                .build();
        when(sessionMapper.selectById(1L)).thenReturn(session);

        assertThatThrownBy(() -> service.getSession(99L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该面试会话");
    }

    @Test
    void finishSession_进行中会话转为报告阶段并触发报告生成() {
        InterviewSession inProgress = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("IN_PROGRESS").questionCount(3)
                .expectedMinQuestions(5).expectedMaxQuestions(8).build();
        InterviewSession reporting = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("REPORTING").questionCount(3)
                .expectedMinQuestions(5).expectedMaxQuestions(8)
                .endedAt(LocalDateTime.of(2026, 8, 9, 11, 0)).build();
        when(sessionMapper.selectById(1L)).thenReturn(inProgress, reporting);
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        when(questionMapper.selectList(any())).thenReturn(List.of());

        com.smartview.generated.web.model.InterviewSession response = service.finishSession(7L, 1L);

        // 提前结束不再直接置 COMPLETED，而是先进 REPORTING 等待报告生成
        assertThat(response.getStatus())
                .isEqualTo(com.smartview.generated.web.model.InterviewSession.StatusEnum.REPORTING);
        assertThat(response.getEndedAt()).isNotNull();
        assertThat(response.getAnswers()).isEmpty();
        // 条件更新必须限定状态为 IN_PROGRESS 且版本自增，防并发覆盖终态
        ArgumentCaptor<LambdaUpdateWrapper<InterviewSession>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sessionMapper).update(isNull(), captor.capture());
        assertThat(captor.getValue().getSqlSet()).contains("end_reason")
                .contains("ended_at").contains("version = version + 1");
        // WHERE 必须同时限定 id 与会话状态，防越权操作他人会话或覆盖终态
        assertThat(captor.getValue().getSqlSegment()).contains("status").contains("id");
        // 条件更新命中 1 行：推进成功后触发报告生成
        verify(reportTaskService).startReportGeneration(any(InterviewSession.class));
    }

    @Test
    void finishSession_提前结束后触发报告生成() {
        InterviewSession inProgress = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("IN_PROGRESS").build();
        InterviewSession reporting = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("REPORTING").build();
        when(sessionMapper.selectById(1L)).thenReturn(inProgress, reporting);
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        when(questionMapper.selectList(any())).thenReturn(List.of());

        service.finishSession(7L, 1L);

        // 报告生成任务在推进事务内触发，传入的会话需携带 id/userId/resumeProfileId 供建行
        ArgumentCaptor<InterviewSession> captor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(reportTaskService).startReportGeneration(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getResumeProfileId()).isEqualTo(10L);
    }

    @Test
    void finishSession_非IN_PROGRESS幂等返回不触发() {
        // 会话已进入 REPORTING（例如其他端已提前结束并生成报告）：幂等返回现状，不重复触发
        InterviewSession reporting = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("REPORTING")
                .endedAt(LocalDateTime.of(2026, 8, 9, 11, 0)).build();
        when(sessionMapper.selectById(1L)).thenReturn(reporting);
        when(questionMapper.selectList(any())).thenReturn(List.of());

        com.smartview.generated.web.model.InterviewSession response = service.finishSession(7L, 1L);

        assertThat(response.getStatus())
                .isEqualTo(com.smartview.generated.web.model.InterviewSession.StatusEnum.REPORTING);
        verify(sessionMapper, never()).update(any(), any());
        verify(reportTaskService, never()).startReportGeneration(any());
    }

    @Test
    void finishSession_会话不存在时返回404() {
        when(sessionMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> service.finishSession(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("面试会话不存在");
    }

    @Test
    void finishSession_非本人会话禁止() {
        InterviewSession session = InterviewSession.builder().id(1L).userId(5L).status("IN_PROGRESS").build();
        when(sessionMapper.selectById(1L)).thenReturn(session);
        assertThatThrownBy(() -> service.finishSession(7L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问该面试会话");
        verify(sessionMapper, never()).update(any(), any());
    }

    @Test
    void finishSession_已终态会话幂等返回且不再更新() {
        InterviewSession completed = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("COMPLETED").questionCount(3)
                .endedAt(LocalDateTime.of(2026, 8, 9, 11, 0)).build();
        when(sessionMapper.selectById(1L)).thenReturn(completed);
        when(questionMapper.selectList(any())).thenReturn(List.of());

        com.smartview.generated.web.model.InterviewSession response = service.finishSession(7L, 1L);

        assertThat(response.getStatus())
                .isEqualTo(com.smartview.generated.web.model.InterviewSession.StatusEnum.COMPLETED);
        verify(sessionMapper, never()).update(any(), any());
        verify(reportTaskService, never()).startReportGeneration(any());
    }

    @Test
    void finishSession_并发下条件更新0行时按现状返回() {
        InterviewSession inProgress = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("IN_PROGRESS").build();
        InterviewSession concurrentReporting = InterviewSession.builder()
                .id(1L).userId(7L).resumeProfileId(10L).status("REPORTING").endedAt(LocalDateTime.now()).build();
        when(sessionMapper.selectById(1L)).thenReturn(inProgress, concurrentReporting);
        when(sessionMapper.update(isNull(), any())).thenReturn(0);
        when(questionMapper.selectList(any())).thenReturn(List.of());

        com.smartview.generated.web.model.InterviewSession response = service.finishSession(7L, 1L);

        // 并发写入者已把会话推进到报告阶段：以服务端现状为准，不抛错
        assertThat(response.getStatus())
                .isEqualTo(com.smartview.generated.web.model.InterviewSession.StatusEnum.REPORTING);
        // 条件更新 0 行：非本次推进成功，不得重复触发报告生成
        verify(reportTaskService, never()).startReportGeneration(any());
    }
}
