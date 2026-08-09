package com.smartview.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.AiServiceProperties;
import com.smartview.interview.model.CandidatePoolItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 面试 AI 客户端测试。
 *
 * 覆盖：成功解析响应并透传鉴权/追踪头、HTTP 异常映射、401 鉴权错误单独提示、
 * 空响应体处理、非法阶段计划兜底为空对象。
 */
@ExtendWith(MockitoExtension.class)
class AiInterviewClientTest {

    @Mock
    private RestTemplate restTemplate;

    private AiInterviewClient client;
    private AiServiceProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiServiceProperties();
        properties.setBaseUrl("http://localhost:8000");
        properties.setApiKey("secret-key");
        client = new AiInterviewClient(restTemplate, properties, new ObjectMapper());
    }

    private AiFirstQuestionResponse successBody() {
        AiFirstQuestionResponse response = new AiFirstQuestionResponse();
        response.setSuccess(true);
        response.setQuestionText("请解释 happens-before 原则。");
        response.setTopic("Java 并发");
        response.setQuestionType("OPENING");
        response.setSourceType("KNOWLEDGE_BASE");
        return response;
    }

    @Test
    void generateFirstQuestion_成功解析响应并透传鉴权与追踪头() {
        when(restTemplate.exchange(
                eq("http://localhost:8000/api/v1/interview/first-question"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(AiFirstQuestionResponse.class)))
                .thenReturn(ResponseEntity.ok(successBody()));

        AiFirstQuestionResponse result = client.generateFirstQuestion(
                1L, "JAVA_BACKEND", "{\"policy_version\":\"1.0\"}", 10L, 1, "trace-123");

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getQuestionText()).isEqualTo("请解释 happens-before 原则。");

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(AiFirstQuestionResponse.class));
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("X-API-Key")).isEqualTo("secret-key");
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("trace-123");
        // 请求体透传 traceId 与阶段计划
        AiFirstQuestionRequest body = (AiFirstQuestionRequest) entityCaptor.getValue().getBody();
        assertThat(body.getTraceId()).isEqualTo("trace-123");
        assertThat(body.getRoleDirection()).isEqualTo("JAVA_BACKEND");
    }

    @Test
    void generateFirstQuestion_连接超时映射为AI服务暂不可用() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(AiFirstQuestionResponse.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.generateFirstQuestion(1L, "JAVA_BACKEND", "{}", 10L, 1, "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 服务暂不可用");
    }

    @Test
    void generateFirstQuestion_401鉴权失败单独提示() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(AiFirstQuestionResponse.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(), new byte[0], null));

        assertThatThrownBy(() -> client.generateFirstQuestion(1L, "JAVA_BACKEND", "{}", 10L, 1, "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("鉴权配置错误");
    }

    @Test
    void generateFirstQuestion_空响应体报错() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(AiFirstQuestionResponse.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.generateFirstQuestion(1L, "JAVA_BACKEND", "{}", 10L, 1, "t"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("空响应");
    }

    @Test
    void generateFirstQuestion_非法阶段计划兜底为空对象() {
        when(restTemplate.exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(AiFirstQuestionResponse.class)))
                .thenReturn(ResponseEntity.ok(successBody()));

        client.generateFirstQuestion(1L, "JAVA_BACKEND", "not-json{{{", 10L, 1, "t");

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(AiFirstQuestionResponse.class));
        AiFirstQuestionRequest body = (AiFirstQuestionRequest) entityCaptor.getValue().getBody();
        // 解析失败时透传空对象，而非整体调用失败
        assertThat(body.getStagePlan()).isInstanceOf(ObjectNode.class);
        assertThat(body.getStagePlan().toString()).isEqualTo("{}");
    }

    @Test
    void generateCandidatePool_成功解析候选列表并透传头() {
        AiGenerateCandidatePoolResponse body = new AiGenerateCandidatePoolResponse();
        body.setSuccess(true);
        // 响应直接复用候选模型 CandidatePoolItem（与 Redis 存储共用同一结构）
        CandidatePoolItem candidate = CandidatePoolItem.builder()
                .questionText("请解释 volatile 语义。")
                .topic("Java 并发")
                .stage("BASIC")
                .candidateType("SAME_STAGE_SWITCH")
                .build();
        body.setCandidates(List.of(candidate));

        when(restTemplate.exchange(
                eq("http://localhost:8000/api/v1/interview/candidate-pool"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(AiGenerateCandidatePoolResponse.class)))
                .thenReturn(ResponseEntity.ok(body));

        AiGenerateCandidatePoolRequest request = new AiGenerateCandidatePoolRequest();
        request.setSessionId("1");
        request.setQuestionId("11");
        request.setRoleDirection("JAVA_BACKEND");
        request.setPoolType("PRE_GENERATED");
        request.setCurrentStage("BASIC");
        request.setTraceId("trace-123");

        AiGenerateCandidatePoolResponse result = client.generateCandidatePool(request);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getCandidates()).hasSize(1);
        assertThat(result.getCandidates().get(0).getTopic()).isEqualTo("Java 并发");

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                anyString(), eq(HttpMethod.POST), entityCaptor.capture(),
                eq(AiGenerateCandidatePoolResponse.class));
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("X-API-Key")).isEqualTo("secret-key");
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("trace-123");
        AiGenerateCandidatePoolRequest sent = (AiGenerateCandidatePoolRequest) entityCaptor.getValue().getBody();
        assertThat(sent.getPoolType()).isEqualTo("PRE_GENERATED");
    }

    @Test
    void generateCandidatePool_连接超时映射为AI服务暂不可用() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(),
                eq(AiGenerateCandidatePoolResponse.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.generateCandidatePool(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 服务暂不可用");
    }

    @Test
    void generateCandidatePool_空响应体报错() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(),
                eq(AiGenerateCandidatePoolResponse.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.generateCandidatePool(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("空响应");
    }

    @Test
    void evaluateAnswer_成功解析评估事实与追问候选并透传头() {
        AiEvaluateAnswerResponse body = new AiEvaluateAnswerResponse();
        body.setSuccess(true);
        body.setScore(85);
        body.setLevel("GOOD");
        body.setMatchedPoints(List.of("可见性"));
        // 追问候选复用完整 CandidatePoolItem（契约改为完整结构）
        CandidatePoolItem followUp = CandidatePoolItem.builder()
                .questionText("volatile 能保证原子性吗？")
                .topic("Java 并发")
                .stage("BASIC")
                .candidateType("FOLLOW_UP")
                .build();
        body.setFollowUpCandidates(List.of(followUp));

        when(restTemplate.exchange(
                eq("http://localhost:8000/api/v1/interview/evaluate"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(AiEvaluateAnswerResponse.class)))
                .thenReturn(ResponseEntity.ok(body));

        AiEvaluateAnswerResponse result = client.evaluateAnswer(evaluateRequest());

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getScore()).isEqualTo(85);
        assertThat(result.getFollowUpCandidates()).hasSize(1);
        assertThat(result.getFollowUpCandidates().get(0).getCandidateType()).isEqualTo("FOLLOW_UP");

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                anyString(), eq(HttpMethod.POST), entityCaptor.capture(),
                eq(AiEvaluateAnswerResponse.class));
        HttpHeaders headers = entityCaptor.getValue().getHeaders();
        assertThat(headers.getFirst("X-API-Key")).isEqualTo("secret-key");
        assertThat(headers.getFirst("X-Trace-Id")).isEqualTo("trace-eval");
        AiEvaluateAnswerRequest sent = (AiEvaluateAnswerRequest) entityCaptor.getValue().getBody();
        assertThat(sent.getQuestionText()).isEqualTo("volatile 的作用？");
        assertThat(sent.getSessionContext().getCurrentStage()).isEqualTo("BASIC");
    }

    @Test
    void evaluateAnswer_连接超时映射为AI服务暂不可用() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(),
                eq(AiEvaluateAnswerResponse.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        assertThatThrownBy(() -> client.evaluateAnswer(evaluateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 服务暂不可用");
    }

    @Test
    void evaluateAnswer_空响应体报错() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(),
                eq(AiEvaluateAnswerResponse.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThatThrownBy(() -> client.evaluateAnswer(evaluateRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("空响应");
    }

    private AiEvaluateAnswerRequest evaluateRequest() {
        AiEvaluateAnswerRequest request = new AiEvaluateAnswerRequest();
        request.setSessionId("1");
        request.setQuestionId("11");
        request.setAnswerText("volatile 保证可见性");
        request.setRoleDirection("JAVA_BACKEND");
        request.setQuestionText("volatile 的作用？");
        request.setTraceId("trace-eval");
        AiEvaluateAnswerRequest.SessionContext context =
                new AiEvaluateAnswerRequest.SessionContext();
        context.setCurrentStage("BASIC");
        context.setCurrentTopic("Java 并发");
        request.setSessionContext(context);
        return request;
    }

    private AiGenerateCandidatePoolRequest request() {
        AiGenerateCandidatePoolRequest request = new AiGenerateCandidatePoolRequest();
        request.setSessionId("1");
        request.setQuestionId("11");
        request.setRoleDirection("JAVA_BACKEND");
        request.setPoolType("PRE_GENERATED");
        request.setCurrentStage("BASIC");
        request.setTraceId("trace-123");
        return request;
    }
}
