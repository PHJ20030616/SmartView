package com.smartview.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.ai.client.AiEvaluateAnswerResponse;
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
import com.smartview.interview.model.CandidatePoolItem;
import com.smartview.report.service.ReportTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回答提交事务落库服务测试：乐观锁推进、REPORTING 置位、覆盖度更新、决策快照。
 */
@ExtendWith(MockitoExtension.class)
class InterviewAnswerTxServiceTest {

    @Mock private InterviewSessionMapper sessionMapper;
    @Mock private InterviewQuestionMapper questionMapper;
    @Mock private InterviewAnswerMapper answerMapper;
    @Mock private AnswerEvaluationMapper answerEvaluationMapper;
    @Mock private ReportTaskService reportTaskService;

    private InterviewAnswerTxService service;

    @BeforeEach
    void setUp() {
        service = new InterviewAnswerTxService(sessionMapper, questionMapper, answerMapper,
                answerEvaluationMapper, new InterviewSessionDtoMapper(), new ObjectMapper(),
                reportTaskService);
    }

    private InterviewSession session() {
        return InterviewSession.builder().id(1L).userId(7L).roleDirection("JAVA_BACKEND")
                .currentStage("BASIC").currentTopic("并发").questionCount(2)
                .stagePlanJson("{\"policy_version\":\"1.0\",\"total_max_questions\":20,"
                        + "\"total_min_questions\":7,"
                        + "\"stages\":[{\"stage\":\"BASIC\",\"min_questions\":3,\"max_questions\":5,"
                        + "\"required_topics\":[\"并发\",\"JVM\"],\"max_follow_up_depth\":2},"
                        + "{\"stage\":\"PROJECT\",\"min_questions\":2,\"max_questions\":5,"
                        + "\"required_topics\":[\"电商\"],\"max_follow_up_depth\":2}]}")
                .stageCoverageJson("{\"BASIC\":{\"question_count\":1,\"covered_topics\":[\"并发\"],"
                        + "\"missing_topics\":[\"JVM\"],\"current_topic_follow_up_count\":0}}")
                .version(3).status("IN_PROGRESS").build();
    }

    private InterviewQuestion current() {
        return InterviewQuestion.builder().id(11L).sessionId(1L).userId(7L)
                .stage("BASIC").questionType("FOLLOW_UP").topic("并发")
                .questionText("并发要点？").status("ASKED").build();
    }

    private SubmitAnswerRequest request() {
        SubmitAnswerRequest req = new SubmitAnswerRequest();
        req.setQuestionId("11");
        req.setAnswerText("回答内容");
        req.setRequestId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        return req;
    }

    private AiEvaluateAnswerResponse eval() {
        AiEvaluateAnswerResponse e = new AiEvaluateAnswerResponse();
        e.setSuccess(true);
        e.setScore(80);
        e.setLevel("GOOD");
        e.setMatchedPoints(List.of("可见性"));
        e.setMissingPoints(List.of());
        e.setRiskPoints(List.of());
        return e;
    }

    private StagePolicyEngine.Decision decision(String action, CandidatePoolItem selected) {
        StagePolicyEngine.Decision d = new StagePolicyEngine.Decision();
        d.setNextAction(action);
        d.setSelectedCandidate(selected);
        d.setDecisionReason("测试决策");
        return d;
    }

    private CandidatePoolItem followUpItem() {
        return CandidatePoolItem.builder().questionText("追问：原子性？").topic("并发")
                .stage("BASIC").candidateType("FOLLOW_UP")
                .sourceType("KNOWLEDGE_BASE").expectedPoints(List.of("原子性")).build();
    }

    @Test
    void persist_optimisticLockConflict_throws() {
        when(sessionMapper.optimisticAdvance(any())).thenReturn(0);

        assertThatThrownBy(() -> service.persist(7L, session(), current(), request(), eval(),
                decision(StagePolicyEngine.ACTION_FOLLOW_UP, followUpItem()), List.of(followUpItem())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("刷新");
    }

    @Test
    void persist_finish_setsReportingAndEndReason() {
        CandidatePoolItem item = followUpItem();
        StagePolicyEngine.Decision d = decision(StagePolicyEngine.ACTION_FINISH, null);
        d.setEndReason(StagePolicyEngine.END_PLAN_COMPLETED);
        when(sessionMapper.optimisticAdvance(any())).thenReturn(1);

        var result = service.persist(7L, session(), current(), request(), eval(), d, List.of(item));

        assertThat(result.getNextQuestion()).isNull();
        assertThat(result.getSessionStatus().getValue()).isEqualTo("REPORTING");
        ArgumentCaptor<InterviewSession> captor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(sessionMapper).optimisticAdvance(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("REPORTING");
        assertThat(captor.getValue().getEndReason()).isEqualTo(StagePolicyEngine.END_PLAN_COMPLETED);
        // version 保持旧值传入，由 SQL 自增（WHERE version = 旧值）
        assertThat(captor.getValue().getVersion()).isEqualTo(3);
    }

    @Test
    void persist_finish_triggersReportGeneration() {
        // 自然结束（ACTION_FINISH）：乐观锁推进成功后必须在同一事务内触发报告生成
        StagePolicyEngine.Decision d = decision(StagePolicyEngine.ACTION_FINISH, null);
        d.setEndReason(StagePolicyEngine.END_PLAN_COMPLETED);
        when(sessionMapper.optimisticAdvance(any())).thenReturn(1);

        service.persist(7L, session(), current(), request(), eval(), d, List.of(followUpItem()));

        verify(reportTaskService).startReportGeneration(any(InterviewSession.class));
    }

    @Test
    void persist_nonFinish_neverTriggersReportGeneration() {
        // 面试未结束（FOLLOW_UP 继续）：不得触发报告生成
        CandidatePoolItem item = followUpItem();
        when(questionMapper.insert(any(InterviewQuestion.class))).thenAnswer(inv -> {
            inv.getArgument(0, InterviewQuestion.class).setId(22L);
            return 1;
        });
        when(sessionMapper.optimisticAdvance(any())).thenReturn(1);

        service.persist(7L, session(), current(), request(), eval(),
                decision(StagePolicyEngine.ACTION_FOLLOW_UP, item), List.of(item));

        verify(reportTaskService, never()).startReportGeneration(any());
    }

    @Test
    void persist_advance_createsNextQuestionAndUpdatesCoverage() {
        CandidatePoolItem item = followUpItem();
        StagePolicyEngine.Decision d = decision(StagePolicyEngine.ACTION_FOLLOW_UP, item);
        when(questionMapper.insert(any(InterviewQuestion.class))).thenAnswer(inv -> {
            inv.getArgument(0, InterviewQuestion.class).setId(22L);
            return 1;
        });
        when(sessionMapper.optimisticAdvance(any())).thenReturn(1);

        var result = service.persist(7L, session(), current(), request(), eval(), d, List.of(item));

        assertThat(result.getNextQuestion().getId()).isEqualTo("22");
        assertThat(result.getSessionStatus().getValue()).isEqualTo("IN_PROGRESS");
        ArgumentCaptor<InterviewSession> captor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(sessionMapper).optimisticAdvance(captor.capture());
        InterviewSession updated = captor.getValue();
        assertThat(updated.getCurrentQuestionId()).isEqualTo(22L);
        assertThat(updated.getCurrentTopic()).isEqualTo("并发");
        assertThat(updated.getQuestionCount()).isEqualTo(3);
        // 本题为追问 → current_topic_follow_up_count +1
        assertThat(updated.getStageCoverageJson()).contains("\"current_topic_follow_up_count\":1");
        // 回答与评估落库，决策快照顶层 candidates 数组
        verify(answerMapper).insert(any(InterviewAnswer.class));
        ArgumentCaptor<AnswerEvaluation> evalCaptor = ArgumentCaptor.forClass(AnswerEvaluation.class);
        verify(answerEvaluationMapper).insert(evalCaptor.capture());
        assertThat(evalCaptor.getValue().getNextAction()).isEqualTo("FOLLOW_UP");
        assertThat(evalCaptor.getValue().getSelectedNextQuestionId()).isEqualTo(22L);
        assertThat(evalCaptor.getValue().getCandidatePoolSnapshotJson()).contains("\"candidates\"");
    }

    @Test
    void persist_missingSelectedCandidateForNonFinish_throws() {
        StagePolicyEngine.Decision d = decision(StagePolicyEngine.ACTION_SWITCH_TOPIC, null);

        assertThatThrownBy(() -> service.persist(7L, session(), current(), request(), eval(),
                d, List.of()))
                .isInstanceOf(BusinessException.class);
        verify(sessionMapper, never()).optimisticAdvance(any());
    }
}
