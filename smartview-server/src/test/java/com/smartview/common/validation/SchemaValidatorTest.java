package com.smartview.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.cleanup.CleanupResultMessage;
import com.smartview.task.mq.ReportGenerateResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SchemaValidator 契约校验测试（report_generate_result / cleanup_result 消息）。
 *
 * 使用真实 ObjectMapper 构造 SchemaValidator 并调用 init() 从 classpath 加载全部
 * 结果 Schema（resume_parse_result / resume_vectorize_result / profile_analyze_result /
 * report_generate_result / cleanup_result），验证：
 * - success=true 成功消息必须携带全部内容字段（reportId/overallScore/.../referenceAnswers）；
 * - success=false 失败消息必须携带 errorMessage；
 * - cleanup_result：success=true 必须携带 cleanedProfileCount，success=false 必须携带 errorMessage；
 * - 缺任一必填内容字段即抛 IllegalArgumentException。
 */
class SchemaValidatorTest {

    private SchemaValidator validator;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 与生产 Bean 一致：真实 ObjectMapper + init() 预加载 classpath 上的契约 Schema。
        objectMapper = new ObjectMapper();
        validator = new SchemaValidator(objectMapper);
        validator.init();
    }

    private CleanupResultMessage cleanupSuccessMessage() {
        return CleanupResultMessage.builder()
                .taskId("00000000-0000-0000-0000-000000000301")
                .traceId("00000000-0000-0000-0000-000000000031")
                .messageType("CLEANUP_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                // 结果消息 createdAt 为 RFC 3339 字符串（透传 worker 时间），与任务消息的 LocalDateTime 区分
                .createdAt("2026-08-20T10:30:00+08:00")
                .bizType("RESUME_FILE")
                .bizId("88")
                .success(true)
                .cleanedProfileCount(2)
                .build();
    }

    private CleanupResultMessage cleanupFailureMessage() {
        return CleanupResultMessage.builder()
                .taskId("00000000-0000-0000-0000-000000000301")
                .traceId("00000000-0000-0000-0000-000000000031")
                .messageType("CLEANUP_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                .createdAt("2026-08-20T10:30:00+08:00")
                .bizType("RESUME_FILE")
                .bizId("88")
                .success(false)
                .errorMessage("MinIO 不可用")
                .build();
    }

    private ReportGenerateResultMessage successMessage() throws Exception {
        return ReportGenerateResultMessage.builder()
                .taskId("00000000-0000-0000-0000-000000000501")
                .traceId("00000000-0000-0000-0000-000000000051")
                .messageType("REPORT_GENERATE_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                .createdAt("2026-08-12T00:00:00Z")
                .sessionId("88")
                .success(true)
                .reportId("5")
                .overallScore(72)
                .readinessLevel("READY")
                .roleFitScore(80)
                .summary("总体评价")
                .strengths(objectMapper.readTree("[\"基础扎实\"]"))
                .weaknesses(objectMapper.readTree("[\"深度不足\"]"))
                .riskPoints(objectMapper.readTree("[\"项目描述空泛\"]"))
                .suggestions(objectMapper.readTree(
                        "[{\"topic\":\"并发\",\"reason\":\"薄弱\",\"resources\":[]}]"))
                .coverage(objectMapper.readTree(
                        "{\"basicCoverage\":0.8,\"projectCoverage\":1.0,\"scenarioCoverage\":0.5}"))
                .referenceAnswers(objectMapper.readTree(
                        "[{\"questionId\":\"10\",\"answerType\":\"BASIC_KEY_POINTS\","
                                + "\"referenceContent\":\"参考答案\",\"keyPoints\":[\"要点\"],\"tradeoffs\":[]}]"))
                .build();
    }

    private ReportGenerateResultMessage failureMessage() {
        return ReportGenerateResultMessage.builder()
                .taskId("00000000-0000-0000-0000-000000000501")
                .traceId("00000000-0000-0000-0000-000000000051")
                .messageType("REPORT_GENERATE_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                .createdAt("2026-08-12T00:00:00Z")
                .sessionId("88")
                .success(false)
                .errorMessage("LLM 服务暂时不可用")
                .build();
    }

    @Test
    void init_loadsAllSchemas() {
        // init() 已执行成功即证明全部结果 Schema 均从 classpath 加载（缺任何一个都会抛异常）
        assertThatCode(() -> validator.init()).doesNotThrowAnyException();
    }

    @Test
    void cleanupSuccessResultWithCleanedCountPasses() {
        // cleanup_result：success=true 且携带 cleanedProfileCount → 契约校验通过
        assertThatCode(() -> validator.validateCleanupResult(cleanupSuccessMessage()))
                .doesNotThrowAnyException();
    }

    @Test
    void cleanupSuccessResultMissingCleanedCountIsRejected() {
        CleanupResultMessage message = cleanupSuccessMessage();
        message.setCleanedProfileCount(null);
        assertThatThrownBy(() -> validator.validateCleanupResult(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void cleanupFailureResultMissingErrorMessageIsRejected() {
        CleanupResultMessage message = cleanupFailureMessage();
        message.setErrorMessage(null);
        assertThatThrownBy(() -> validator.validateCleanupResult(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void cleanupFailureResultWithErrorMessagePasses() {
        assertThatCode(() -> validator.validateCleanupResult(cleanupFailureMessage()))
                .doesNotThrowAnyException();
    }

    @Test
    void successResultWithAllContentFieldsPasses() throws Exception {
        // success=true 且 11 个内容字段齐全 → 契约校验通过
        assertThatCode(() -> validator.validateReportGenerateResult(successMessage()))
                .doesNotThrowAnyException();
    }

    @Test
    void successResultMissingSuggestionsIsRejected() throws Exception {
        // success=true 缺 suggestions（任一内容字段）→ 违反 allOf.then.required → 校验失败
        ReportGenerateResultMessage message = successMessage();
        message.setSuggestions(null);
        assertThatThrownBy(() -> validator.validateReportGenerateResult(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void successResultMissingReferenceAnswersIsRejected() throws Exception {
        ReportGenerateResultMessage message = successMessage();
        message.setReferenceAnswers((JsonNode) null);
        assertThatThrownBy(() -> validator.validateReportGenerateResult(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void failureResultMissingErrorMessageIsRejected() {
        // success=false 缺 errorMessage → 违反 allOf.then.required → 校验失败
        ReportGenerateResultMessage message = failureMessage();
        message.setErrorMessage(null);
        assertThatThrownBy(() -> validator.validateReportGenerateResult(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void failureResultWithErrorMessagePasses() {
        // success=false 且携带 errorMessage → 契约校验通过
        assertThatCode(() -> validator.validateReportGenerateResult(failureMessage()))
                .doesNotThrowAnyException();
    }
}
