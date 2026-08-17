package com.smartview.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.AnswerTradeoff;
import com.smartview.generated.web.model.ReportCoverage;
import com.smartview.generated.web.model.ReportSuggestion;
import com.smartview.interview.dto.AnswerHistoryAssembler;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.report.entity.ReferenceAnswer;
import com.smartview.report.mapper.InterviewReportMapper;
import com.smartview.report.mapper.ReferenceAnswerMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 报告查询服务。
 *
 * 只读查询 + 重试委托，职责与 ReportTaskService（报告生成任务编排）隔离：
 * - 按会话/报告 ID 查询报告，校验归属后组装契约 InterviewReport DTO
 * - 报告失败重试：校验归属与失败态后委托 ReportTaskService 重建任务
 *
 * 组装规则：
 * - 实体 JSON 字段（strengthsJson/coverageJson 等）反序列化为契约数组/对象
 * - answers 复用 AnswerHistoryAssembler 与面试会话历史同一装配逻辑
 * - 参考答案按 reportId 查询，answerType/readinessLevel/status 等枚举安全转换，
 *   未知值缺省而非报错，避免历史脏值导致响应序列化 500
 */
@Slf4j
@Service
public class ReportQueryService {

    private final InterviewReportMapper reportMapper;
    private final ReferenceAnswerMapper referenceAnswerMapper;
    private final InterviewSessionMapper sessionMapper;
    private final AnswerHistoryAssembler answerHistoryAssembler;
    private final ReportTaskService reportTaskService;
    private final ObjectMapper objectMapper;

    public ReportQueryService(
            InterviewReportMapper reportMapper,
            ReferenceAnswerMapper referenceAnswerMapper,
            InterviewSessionMapper sessionMapper,
            AnswerHistoryAssembler answerHistoryAssembler,
            ReportTaskService reportTaskService,
            ObjectMapper objectMapper) {
        this.reportMapper = reportMapper;
        this.referenceAnswerMapper = referenceAnswerMapper;
        this.sessionMapper = sessionMapper;
        this.answerHistoryAssembler = answerHistoryAssembler;
        this.reportTaskService = reportTaskService;
        this.objectMapper = objectMapper;
    }

    /** 按会话查询报告（报告属于该会话时校验用户归属，无报告返回 404）。 */
    @Transactional(readOnly = true)
    public com.smartview.generated.web.model.InterviewReport getReportBySession(
            Long userId, Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "面试会话不存在");
        }
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该面试会话", HttpStatus.FORBIDDEN);
        }
        com.smartview.report.entity.InterviewReport report = findReportBySession(sessionId);
        return toDto(report, session);
    }

    /** 按报告 ID 查询报告（校验报告归属，不存在返回 404）。 */
    @Transactional(readOnly = true)
    public com.smartview.generated.web.model.InterviewReport getReport(Long userId, Long reportId) {
        com.smartview.report.entity.InterviewReport report = findReport(reportId, userId);
        InterviewSession session = sessionMapper.selectById(report.getSessionId());
        return toDto(report, session);
    }

    /**
     * 报告失败后重试：仅 FAILED 报告真正重建（委托 ReportTaskService），
     * GENERATING/SUCCESS 幂等返回现状；重试后返回最新报告状态供页面继续轮询。
     */
    @Transactional(rollbackFor = Exception.class)
    public com.smartview.generated.web.model.InterviewReport retryReport(Long userId, Long reportId) {
        com.smartview.report.entity.InterviewReport report = findReport(reportId, userId);
        reportTaskService.retryReportGeneration(report);
        // 重试可能原地更新了报告状态（FAILED→GENERATING），重新查询以返回最新现状。
        com.smartview.report.entity.InterviewReport refreshed = reportMapper.selectById(reportId);
        InterviewSession session = sessionMapper.selectById(refreshed.getSessionId());
        return toDto(refreshed, session);
    }

    // ==================== 私有辅助 ====================

    private com.smartview.report.entity.InterviewReport findReport(Long reportId, Long userId) {
        com.smartview.report.entity.InterviewReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "面试报告不存在");
        }
        if (!userId.equals(report.getUserId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该面试报告", HttpStatus.FORBIDDEN);
        }
        return report;
    }

    private com.smartview.report.entity.InterviewReport findReportBySession(Long sessionId) {
        com.smartview.report.entity.InterviewReport report = reportMapper.selectOne(
                new LambdaQueryWrapper<com.smartview.report.entity.InterviewReport>()
                        .eq(com.smartview.report.entity.InterviewReport::getSessionId, sessionId));
        if (report == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "该会话的报告尚未生成");
        }
        return report;
    }

    /** 实体 → 契约 InterviewReport DTO（枚举安全转换，JSON 字段反序列化）。 */
    private com.smartview.generated.web.model.InterviewReport toDto(
            com.smartview.report.entity.InterviewReport entity, InterviewSession session) {
        return new com.smartview.generated.web.model.InterviewReport(
                entity.getId().toString(),
                entity.getSessionId().toString(),
                entity.getUserId().toString(),
                safeStatus(entity.getStatus()))
                .resumeProfileId(entity.getResumeProfileId() == null ? null : entity.getResumeProfileId().toString())
                .roleDirection(safeDirection(session == null ? null : session.getRoleDirection()))
                .overallScore(entity.getOverallScore())
                .readinessLevel(safeReadiness(entity.getReadinessLevel()))
                .roleFitScore(entity.getRoleFitScore())
                .summary(entity.getSummary())
                .strengths(parseStringList(entity.getStrengthsJson()))
                .weaknesses(parseStringList(entity.getWeaknessesJson()))
                .riskPoints(parseStringList(entity.getRiskPointsJson()))
                .suggestions(parseSuggestions(entity.getSuggestionsJson()))
                .coverage(parseCoverage(entity.getCoverageJson()))
                .referenceAnswers(loadReferenceAnswerDtos(entity.getId()))
                .answers(answerHistoryAssembler.load(entity.getSessionId()))
                .generatedAt(toOffsetDateTime(entity.getGeneratedAt()));
    }

    private List<com.smartview.generated.web.model.ReferenceAnswer> loadReferenceAnswerDtos(Long reportId) {
        return referenceAnswerMapper.selectList(
                        new LambdaQueryWrapper<ReferenceAnswer>()
                                .eq(ReferenceAnswer::getReportId, reportId)
                                .orderByAsc(ReferenceAnswer::getQuestionId))
                .stream()
                .map(this::toReferenceAnswerDto)
                .toList();
    }

    private com.smartview.generated.web.model.ReferenceAnswer toReferenceAnswerDto(ReferenceAnswer entity) {
        return new com.smartview.generated.web.model.ReferenceAnswer(
                entity.getQuestionId().toString(),
                safeAnswerType(entity.getAnswerType()),
                entity.getReferenceContent())
                .id(entity.getId() == null ? null : entity.getId().toString())
                .keyPoints(parseStringList(entity.getKeyPointsJson()))
                .tradeoffs(parseTradeoffs(entity.getTradeoffsJson()));
    }

    // ==================== JSON 反序列化 ====================

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            Object value = objectMapper.readValue(json, List.class);
            return value == null ? new ArrayList<>() : (List<String>) value;
        } catch (JsonProcessingException exception) {
            log.warn("报告字符串数组字段解析失败，按空处理，json={}", json, exception);
            return new ArrayList<>();
        }
    }

    private List<ReportSuggestion> parseSuggestions(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readerForListOf(ReportSuggestion.class).readValue(json);
        } catch (JsonProcessingException exception) {
            log.warn("报告建议字段解析失败，按空处理，json={}", json, exception);
            return new ArrayList<>();
        }
    }

    private ReportCoverage parseCoverage(String json) {
        if (json == null || json.isBlank()) {
            return new ReportCoverage(); // 缺省全 0/空
        }
        try {
            return objectMapper.readValue(json, ReportCoverage.class);
        } catch (JsonProcessingException exception) {
            log.warn("报告覆盖度字段解析失败，按空对象处理，json={}", json, exception);
            return new ReportCoverage();
        }
    }

    private List<AnswerTradeoff> parseTradeoffs(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readerForListOf(AnswerTradeoff.class).readValue(json);
        } catch (JsonProcessingException exception) {
            log.warn("参考答案权衡点解析失败，按空处理", exception);
            return new ArrayList<>();
        }
    }

    // ==================== 安全枚举转换 ====================

    private com.smartview.generated.web.model.InterviewReport.StatusEnum safeStatus(String code) {
        if (code == null) return null;
        try {
            return com.smartview.generated.web.model.InterviewReport.StatusEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("报告状态值未知，响应缺省，status={}", code);
            return null;
        }
    }

    private com.smartview.generated.web.model.InterviewReport.ReadinessLevelEnum safeReadiness(String code) {
        if (code == null) return null;
        try {
            return com.smartview.generated.web.model.InterviewReport.ReadinessLevelEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("准备度等级值未知，响应缺省，level={}", code);
            return null;
        }
    }

    private com.smartview.generated.web.model.InterviewReport.RoleDirectionEnum safeDirection(String code) {
        if (code == null) return null;
        try {
            return com.smartview.generated.web.model.InterviewReport.RoleDirectionEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("面试方向值未知，响应缺省，direction={}", code);
            return null;
        }
    }

    private com.smartview.generated.web.model.ReferenceAnswer.AnswerTypeEnum safeAnswerType(String code) {
        if (code == null) return null;
        try {
            return com.smartview.generated.web.model.ReferenceAnswer.AnswerTypeEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("参考答案类型值未知，响应缺省，type={}", code);
            return null;
        }
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
