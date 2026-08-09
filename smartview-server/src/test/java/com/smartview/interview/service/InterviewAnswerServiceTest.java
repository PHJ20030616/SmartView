package com.smartview.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.ai.client.AiEvaluateAnswerResponse;
import com.smartview.ai.client.AiInterviewClient;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.SubmitAnswerRequest;
import com.smartview.interview.dto.InterviewSessionDtoMapper;
import com.smartview.interview.engine.StagePolicyEngine;
import com.smartview.interview.entity.AnswerEvaluation;
import com.smartview.interview.entity.InterviewAnswer;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewAnswerMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.interview.mapper.InterviewSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回答提交编排服务测试：幂等命中、过期题目拒绝、评估失败不落库、正常推进与结束。
 */
@ExtendWith(MockitoExtension.class)
class InterviewAnswerServiceTest {

    @Mock private InterviewSessionMapper sessionMapper;
    @Mock private InterviewQuestionMapper questionMapper;
    @Mock private InterviewAnswerMapper answerMapper;
    @Mock private AnswerEvaluationMapper answerEvaluationMapper;
    @Mock private AiInterviewClient aiInterviewClient;
    @Mock private FollowUpPoolService followUpPoolService;
    @Mock private StagePolicyEngine stagePolicyEngine;
    @Mock private InterviewAnswerTxService answerTxService;

    private InterviewAnswerService service;

    @BeforeEach
    void setUp() {
        service = new InterviewAnswerService(sessionMapper, questionMapper, answerMapper,
                answerEvaluationMapper, aiInterviewClient, followUpPoolService,
                stagePolicyEngine, answerTxService, new InterviewSessionDtoMapper(),
                new ObjectMapper());
    }

    private InterviewSession session() {
        return InterviewSession.builder().id(1L).userId(7L).roleDirection("JAVA_BACKEND")
                .currentStage("BASIC").currentTopic("并发").questionCount(2)
                .status("IN_PROGRESS").currentQuestionId(11L)
                .stagePlanJson("{}").stageCoverageJson("{}").version(3).build();
    }

    private InterviewQuestion currentQuestion() {
        return InterviewQuestion.builder().id(11L).sessionId(1L).userId(7L)
                .stage("BASIC").questionType("OPENING").topic("并发")
                .questionText("并发要点？").expectedPointsJson("[\"可见性\"]").status("ASKED").build();
    }

    private SubmitAnswerRequest request(String requestId) {
        SubmitAnswerRequest req = new SubmitAnswerRequest();
        req.setQuestionId("11");
        req.setAnswerText("回答内容");
        req.setRequestId(UUID.fromString(requestId));
        return req;
    }

    private AiEvaluateAnswerResponse okEval() {
        AiEvaluateAnswerResponse e = new AiEvaluateAnswerResponse();
        e.setSuccess(true);
        e.setScore(80);
        e.setLevel("GOOD");
        e.setMatchedPoints(List.of("可见性"));
        e.setMissingPoints(List.of());
        e.setRiskPoints(List.of());
        return e;
    }

    @Test
    void submitAnswer_requestIdExists_returnsExistingWithoutPersist() {
        when(answerMapper.selectOne(any())).thenReturn(
                InterviewAnswer.builder().id(100L).sessionId(1L).questionId(11L).build());
        when(answerEvaluationMapper.selectOne(any())).thenReturn(
                AnswerEvaluation.builder().id(9L).score(80).level("GOOD")
                        .nextAction("FOLLOW_UP").selectedNextQuestionId(22L).build());
        when(questionMapper.selectById(22L)).thenReturn(
                InterviewQuestion.builder().id(22L).sessionId(1L)
                        .questionOrder(3).questionText("下一题").build());

        var result = service.submitAnswer(7L, 1L, request("00000000-0000-0000-0000-000000000001"));

        assertThat(result.getAnswerId()).isEqualTo("100");
        verify(answerTxService, never()).persist(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitAnswer_expiredQuestion_rejected() {
        when(sessionMapper.selectById(1L)).thenReturn(session());
        SubmitAnswerRequest req = request("00000000-0000-0000-0000-000000000002");
        req.setQuestionId("999");  // 非当前题（当前为 11）

        assertThatThrownBy(() -> service.submitAnswer(7L, 1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前题目");
        verify(aiInterviewClient, never()).evaluateAnswer(any());
    }

    @Test
    void submitAnswer_notOwner_rejected() {
        when(sessionMapper.selectById(1L)).thenReturn(session());

        assertThatThrownBy(() -> service.submitAnswer(99L, 1L, request("00000000-0000-0000-0000-000000000003")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void submitAnswer_evaluateFailure_doesNotPersist() {
        when(sessionMapper.selectById(1L)).thenReturn(session());
        when(questionMapper.selectById(11L)).thenReturn(currentQuestion());
        AiEvaluateAnswerResponse fail = new AiEvaluateAnswerResponse();
        fail.setSuccess(false);
        fail.setErrorMessage("AI 繁忙");
        when(aiInterviewClient.evaluateAnswer(any())).thenReturn(fail);

        assertThatThrownBy(() -> service.submitAnswer(7L, 1L, request("00000000-0000-0000-0000-000000000004")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("评估失败");
        verify(answerTxService, never()).persist(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void submitAnswer_happyPath_persistsAndPreGeneratesNext() {
        when(sessionMapper.selectById(1L)).thenReturn(session());
        when(questionMapper.selectById(11L)).thenReturn(currentQuestion());
        when(aiInterviewClient.evaluateAnswer(any())).thenReturn(okEval());
        when(followUpPoolService.getPool(any(), anyLong(), any())).thenReturn(List.of());
        StagePolicyEngine.Decision decision = new StagePolicyEngine.Decision();
        decision.setNextAction(StagePolicyEngine.ACTION_FOLLOW_UP);
        when(stagePolicyEngine.decide(any())).thenReturn(decision);

        com.smartview.generated.web.model.SubmitAnswerData data =
                new com.smartview.generated.web.model.SubmitAnswerData(
                        "100", new com.smartview.generated.web.model.AnswerEvaluation(
                                80, com.smartview.generated.web.model.AnswerEvaluation.LevelEnum.GOOD))
                        .nextQuestion(new com.smartview.generated.web.model.InterviewQuestion(
                                "22", "1", 3, "下一题"));
        when(answerTxService.persist(any(), any(), any(), any(), any(), any(), any())).thenReturn(data);

        var result = service.submitAnswer(7L, 1L, request("00000000-0000-0000-0000-000000000005"));

        assertThat(result.getNextQuestion().getId()).isEqualTo("22");
        verify(followUpPoolService).preGenerateAsync(1L, 22L);
    }
}
