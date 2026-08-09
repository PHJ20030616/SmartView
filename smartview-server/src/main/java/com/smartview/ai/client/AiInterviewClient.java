package com.smartview.ai.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.AiServiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * 面向 FastAPI 的面试 AI 客户端（同步 HTTP 调用）。
 *
 * 功能说明：
 * - 封装"生成首题"接口的 HTTP 调用细节，业务代码只依赖本客户端
 * - 负责请求头 X-API-Key 鉴权、traceId 透传与统一的异常转换
 *
 * 架构边界（CLAUDE.md 调用链规则）：
 * - Spring Boot 后端调用 FastAPI AI 服务必须通过统一客户端，禁止业务代码
 *   直接使用 RestTemplate 或其他 HTTP 客户端；本类即面试域的统一入口
 * - 生产主链路（画像分析等）仍走 MQ 异步；首题生成因"创建会话需同步返回首题"
 *   的验收要求，使用 HTTP 同步通道
 *
 * 容错说明：
 * - 连接/读取超时由 AiServiceConfig 配置的 RestTemplate 控制
 * - HTTP 层异常（超时、5xx、401 等）统一转换为 BusinessException，
 *   由调用方（创建会话事务）整体回滚，不留下无首题的孤儿会话
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Slf4j
@Component
public class AiInterviewClient {

    /** 跨服务鉴权请求头名称，与 FastAPI 端 ai_service_api_key 校验保持一致 */
    private static final String X_API_KEY_HEADER = "X-API-Key";

    /** 首题生成接口路径，与 ai-api 契约一致 */
    private static final String FIRST_QUESTION_PATH = "/api/v1/interview/first-question";

    /** 候选池生成接口路径，与 ai-api 契约一致 */
    private static final String CANDIDATE_POOL_PATH = "/api/v1/interview/candidate-pool";

    /** 回答评估接口路径，与 ai-api 契约一致 */
    private static final String EVALUATE_PATH = "/api/v1/interview/evaluate";

    private final RestTemplate restTemplate;
    private final AiServiceProperties properties;
    private final ObjectMapper objectMapper;

    public AiInterviewClient(
            @Qualifier("aiServiceRestTemplate") RestTemplate restTemplate,
            AiServiceProperties properties,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 FastAPI 生成首题。
     *
     * @param sessionId        面试会话 ID
     * @param roleDirection    面试方向，用于 FastAPI 检索过滤与出题上下文
     * @param stagePlanJson    阶段计划 JSON（snake_case，原样透传）
     * @param resumeProfileId  简历画像 ID
     * @param profileVersion   简历画像版本号
     * @param traceId          链路追踪 ID
     * @return FastAPI 返回的首题结果（含正文、主题、来源与引用）
     */
    public AiFirstQuestionResponse generateFirstQuestion(
            Long sessionId,
            String roleDirection,
            String stagePlanJson,
            Long resumeProfileId,
            Integer profileVersion,
            String traceId) {
        AiFirstQuestionRequest request = new AiFirstQuestionRequest();
        request.setSessionId(String.valueOf(sessionId));
        request.setRoleDirection(roleDirection);
        request.setStagePlan(parseStagePlan(stagePlanJson));
        request.setResumeProfileId(String.valueOf(resumeProfileId));
        request.setProfileVersion(profileVersion);
        request.setTraceId(traceId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(X_API_KEY_HEADER, properties.getApiKey());
        // 请求头透传 traceId，与 ai-api 契约 XTraceId 头保持一致，便于跨服务日志关联。
        headers.set(TraceIdContext.TRACE_ID_HEADER, traceId);

        String url = properties.getBaseUrl().replaceAll("/+$", "") + FIRST_QUESTION_PATH;
        try {
            log.info("调用 FastAPI 生成首题，sessionId={}, resumeProfileId={}, version={}",
                    sessionId, resumeProfileId, profileVersion);
            ResponseEntity<AiFirstQuestionResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    AiFirstQuestionResponse.class);
            AiFirstQuestionResponse body = response.getBody();
            if (body == null) {
                throw new BusinessException(ResponseCode.INTERNAL_ERROR, "AI 服务返回空响应，首题生成失败");
            }
            return body;
        } catch (RestClientException exception) {
            // 401 多为本地未配置/配错 AI_SERVICE_API_KEY，单独提示便于排障；
            // 其余超时、连接失败、5xx 归为 AI 服务暂不可用，避免原始堆栈泄漏给前端。
            if (exception instanceof HttpStatusCodeException statusException
                    && HttpStatus.UNAUTHORIZED.value() == statusException.getStatusCode().value()) {
                log.error("AI 服务鉴权失败（401），请检查 AI_SERVICE_API_KEY 配置，sessionId={}", sessionId);
                throw new BusinessException(
                        ResponseCode.INTERNAL_ERROR, "AI 服务鉴权配置错误，请联系管理员");
            }
            log.error("AI 服务首题生成调用失败，sessionId={}, error={}",
                    sessionId, exception.getMessage(), exception);
            throw new BusinessException(
                    ResponseCode.INTERNAL_ERROR, "AI 服务暂不可用，首题生成失败，请稍后重试");
        }
    }

    /**
     * 调用 FastAPI 生成候选问题池。
     *
     * 候选池是尽力而为的缓存：调用失败抛 BusinessException 由调用方决定降级策略
     * （预生成异步路径捕获后仅记日志；同步重建路径捕获后返回空池）。
     *
     * @param request 候选池生成请求（poolType 区分预生成/追问）
     * @return FastAPI 返回的候选池结果
     */
    public AiGenerateCandidatePoolResponse generateCandidatePool(AiGenerateCandidatePoolRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(X_API_KEY_HEADER, properties.getApiKey());
        headers.set(TraceIdContext.TRACE_ID_HEADER, request.getTraceId());

        String url = properties.getBaseUrl().replaceAll("/+$", "") + CANDIDATE_POOL_PATH;
        try {
            log.info("调用 FastAPI 生成候选池，sessionId={}, questionId={}, poolType={}",
                    request.getSessionId(), request.getQuestionId(), request.getPoolType());
            ResponseEntity<AiGenerateCandidatePoolResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    AiGenerateCandidatePoolResponse.class);
            AiGenerateCandidatePoolResponse body = response.getBody();
            if (body == null) {
                throw new BusinessException(ResponseCode.INTERNAL_ERROR, "AI 服务返回空响应，候选池生成失败");
            }
            return body;
        } catch (RestClientException exception) {
            if (exception instanceof HttpStatusCodeException statusException
                    && HttpStatus.UNAUTHORIZED.value() == statusException.getStatusCode().value()) {
                log.error("AI 服务鉴权失败（401），请检查 AI_SERVICE_API_KEY 配置，sessionId={}",
                        request.getSessionId());
                throw new BusinessException(
                        ResponseCode.INTERNAL_ERROR, "AI 服务鉴权配置错误，请联系管理员");
            }
            log.error("AI 服务候选池生成调用失败，sessionId={}, error={}",
                    request.getSessionId(), exception.getMessage(), exception);
            throw new BusinessException(
                    ResponseCode.INTERNAL_ERROR, "AI 服务暂不可用，候选池生成失败，请稍后重试");
        }
    }

    /**
     * 调用 FastAPI 评估回答并获取追问候选。
     *
     * 评估失败（success=false）时由调用方决定：不落库回答，返回错误允许用户重试
     * （interview-policy.md 5.2「仍然失败→返回错误，允许用户重试」），
     * 避免在无评估事实的情况下推进会话。
     *
     * @param request 回答评估请求（含题目正文/期望要点/阶段计划）
     * @return FastAPI 返回的评估事实与追问候选
     */
    public AiEvaluateAnswerResponse evaluateAnswer(AiEvaluateAnswerRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(X_API_KEY_HEADER, properties.getApiKey());
        headers.set(TraceIdContext.TRACE_ID_HEADER, request.getTraceId());

        String url = properties.getBaseUrl().replaceAll("/+$", "") + EVALUATE_PATH;
        try {
            log.info("调用 FastAPI 评估回答，sessionId={}, questionId={}",
                    request.getSessionId(), request.getQuestionId());
            ResponseEntity<AiEvaluateAnswerResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    AiEvaluateAnswerResponse.class);
            AiEvaluateAnswerResponse body = response.getBody();
            if (body == null) {
                throw new BusinessException(ResponseCode.INTERNAL_ERROR, "AI 服务返回空响应，回答评估失败");
            }
            return body;
        } catch (RestClientException exception) {
            if (exception instanceof HttpStatusCodeException statusException
                    && HttpStatus.UNAUTHORIZED.value() == statusException.getStatusCode().value()) {
                log.error("AI 服务鉴权失败（401），请检查 AI_SERVICE_API_KEY 配置，sessionId={}",
                        request.getSessionId());
                throw new BusinessException(
                        ResponseCode.INTERNAL_ERROR, "AI 服务鉴权配置错误，请联系管理员");
            }
            log.error("AI 服务回答评估调用失败，sessionId={}, error={}",
                    request.getSessionId(), exception.getMessage(), exception);
            throw new BusinessException(
                    ResponseCode.INTERNAL_ERROR, "AI 服务暂不可用，回答评估失败，请稍后重试");
        }
    }

    /**
     * 将阶段计划字符串解析为 JSON 节点透传；空值返回空对象，避免请求体字段为 null。
     */
    private JsonNode parseStagePlan(String stagePlanJson) {
        if (stagePlanJson == null || stagePlanJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(stagePlanJson);
        } catch (JsonProcessingException exception) {
            // 阶段计划由本服务生成并序列化，正常情况下不会解析失败；
            // 兜底为空对象，让 FastAPI 走默认出题逻辑而不是调用失败。
            log.warn("阶段计划 JSON 解析失败，按空计划透传，error={}", exception.getMessage());
            return new ObjectNode(objectMapper.getNodeFactory());
        }
    }
}
