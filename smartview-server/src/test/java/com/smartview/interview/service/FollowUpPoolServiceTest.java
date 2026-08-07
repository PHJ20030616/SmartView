package com.smartview.interview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartview.ai.client.AiGenerateCandidatePoolRequest;
import com.smartview.ai.client.AiGenerateCandidatePoolResponse;
import com.smartview.ai.client.AiInterviewClient;
import com.smartview.interview.entity.AnswerEvaluation;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.interview.model.CandidatePoolItem;
import com.smartview.infra.redis.CandidatePoolRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 候选池服务测试。
 *
 * 覆盖验收标准：
 * 1. 预生成异步：组装 PRE_GENERATED 请求、调用 AI 客户端、写入 Redis；
 *    AI 失败/异常不向上抛出（候选池可降级）
 * 2. getPool：Redis 命中直接返回；缺失走快照重建（5 分钟内）；快照也缺失走
 *    同步重生成；全部失败返回空
 * 3. mergeFollowUps：将追问池委托给 Redis 仓库并入
 */
@ExtendWith(MockitoExtension.class)
class FollowUpPoolServiceTest {

    private static final String KEY = "interview:candidate_pool:1:11:BASIC";

    @Mock
    private InterviewSessionMapper sessionMapper;
    @Mock
    private InterviewQuestionMapper questionMapper;
    @Mock
    private AnswerEvaluationMapper answerEvaluationMapper;
    @Mock
    private CandidatePoolRedisRepository redisRepository;
    @Mock
    private AiInterviewClient aiInterviewClient;

    private FollowUpPoolService service;

    @BeforeEach
    void setUp() {
        service = new FollowUpPoolService(
                sessionMapper, questionMapper, answerEvaluationMapper,
                redisRepository, aiInterviewClient, new ObjectMapper());
    }

    private InterviewSession session() {
        return InterviewSession.builder()
                .id(1L)
                .userId(7L)
                .roleDirection("JAVA_BACKEND")
                .currentStage("BASIC")
                .currentTopic("Java 并发")
                .questionCount(1)
                .stagePlanJson("{\"policy_version\":\"1.0\",\"stages\":[]}")
                .build();
    }

    private CandidatePoolItem item(String type, String topic) {
        return CandidatePoolItem.builder()
                .questionText("关于" + topic + "的问题。")
                .topic(topic)
                .stage("BASIC")
                .candidateType(type)
                .sourceType("KNOWLEDGE_BASE")
                .build();
    }

    private AiGenerateCandidatePoolResponse poolResponse(CandidatePoolItem... items) {
        AiGenerateCandidatePoolResponse response = new AiGenerateCandidatePoolResponse();
        response.setSuccess(true);
        response.setCandidates(List.of(items));
        return response;
    }

    @Test
    void preGenerateAsync_组装请求并写入Redis() {
        when(sessionMapper.selectById(1L)).thenReturn(session());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(aiInterviewClient.generateCandidatePool(any()))
                .thenReturn(poolResponse(item("SAME_STAGE_SWITCH", "JVM")));

        service.preGenerateAsync(1L, 11L);

        ArgumentCaptor<AiGenerateCandidatePoolRequest> captor =
                ArgumentCaptor.forClass(AiGenerateCandidatePoolRequest.class);
        verify(aiInterviewClient).generateCandidatePool(captor.capture());
        assertThat(captor.getValue().getPoolType()).isEqualTo("PRE_GENERATED");
        assertThat(captor.getValue().getCurrentStage()).isEqualTo("BASIC");
        assertThat(captor.getValue().getStagePlan()).isNotNull();

        verify(redisRepository).save(eq(KEY), anyList());
    }

    @Test
    void preGenerateAsync_AI失败不抛出() {
        when(sessionMapper.selectById(1L)).thenReturn(session());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        AiGenerateCandidatePoolResponse failed = new AiGenerateCandidatePoolResponse();
        failed.setSuccess(false);
        failed.setErrorMessage("生成失败");
        when(aiInterviewClient.generateCandidatePool(any())).thenReturn(failed);

        service.preGenerateAsync(1L, 11L);

        verify(redisRepository, never()).save(anyString(), anyList());
    }

    @Test
    void preGenerateAsync_调用异常不抛出() {
        when(sessionMapper.selectById(1L)).thenReturn(session());
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(aiInterviewClient.generateCandidatePool(any()))
                .thenThrow(new com.smartview.common.exception.BusinessException(
                        com.smartview.common.api.ResponseCode.INTERNAL_ERROR, "AI 服务暂不可用"));

        service.preGenerateAsync(1L, 11L);

        verify(redisRepository, never()).save(anyString(), anyList());
    }

    @Test
    void getPool_Redis命中直接返回() {
        List<CandidatePoolItem> pool = List.of(item("SAME_STAGE_SWITCH", "JVM"));
        when(redisRepository.read(KEY)).thenReturn(pool);

        assertThat(service.getPool(session(), 11L)).isEqualTo(pool);
        verify(answerEvaluationMapper, never()).selectOne(any());
    }

    @Test
    void getPool_Redis缺失且快照新鲜时用快照() {
        when(redisRepository.read(KEY)).thenReturn(null);

        AnswerEvaluation evaluation = AnswerEvaluation.builder()
                .id(1L)
                .sessionId(1L)
                .createdAt(LocalDateTime.now())
                .candidatePoolSnapshotJson(
                        "{\"candidates\":[{\"questionText\":\"快照题\",\"topic\":\"JVM\","
                        + "\"stage\":\"BASIC\",\"candidateType\":\"SAME_STAGE_SWITCH\"}]}")
                .build();
        when(answerEvaluationMapper.selectOne(any())).thenReturn(evaluation);

        List<CandidatePoolItem> pool = service.getPool(session(), 11L);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).getTopic()).isEqualTo("JVM");
        // 快照命中也写回 Redis，避免下次再查快照
        verify(redisRepository).save(eq(KEY), anyList());
    }

    @Test
    void getPool_Redis缺失且快照过期时同步重生成() {
        when(redisRepository.read(KEY)).thenReturn(null);

        AnswerEvaluation evaluation = AnswerEvaluation.builder()
                .id(1L)
                .sessionId(1L)
                .createdAt(LocalDateTime.now().minusMinutes(10)) // 超过 5 分钟视为过期
                .candidatePoolSnapshotJson(
                        "{\"candidates\":[{\"questionText\":\"旧题\",\"topic\":\"旧\","
                        + "\"stage\":\"BASIC\",\"candidateType\":\"SAME_STAGE_SWITCH\"}]}")
                .build();
        when(answerEvaluationMapper.selectOne(any())).thenReturn(evaluation);
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(aiInterviewClient.generateCandidatePool(any()))
                .thenReturn(poolResponse(item("SAME_STAGE_SWITCH", "JVM")));

        List<CandidatePoolItem> pool = service.getPool(session(), 11L);

        assertThat(pool).hasSize(1);
        assertThat(pool.get(0).getTopic()).isEqualTo("JVM");
        verify(redisRepository).save(eq(KEY), anyList());
    }

    @Test
    void getPool_全链路失败返回空() {
        when(redisRepository.read(KEY)).thenReturn(null);
        when(answerEvaluationMapper.selectOne(any())).thenReturn(null);
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(aiInterviewClient.generateCandidatePool(any()))
                .thenThrow(new com.smartview.common.exception.BusinessException(
                        com.smartview.common.api.ResponseCode.INTERNAL_ERROR, "AI 服务暂不可用"));

        assertThat(service.getPool(session(), 11L)).isEmpty();
    }

    @Test
    void mergeFollowUps_委托Redis仓库并入() {
        service.mergeFollowUps(session(), 11L, List.of(item("FOLLOW_UP", "追问")));
        verify(redisRepository).mergeFollowUps(eq(KEY), anyList());
    }
}
