package com.smartview.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * 分页查询当前用户的报告历史摘要（列表专用轻量模型）。
     *
     * 业务规则：
     * - 只返回当前用户数据（userId 精确匹配），已软删除报告由 @TableLogic 自动过滤
     * - 按创建时间倒序，最新生成的报告排在前面
     * - 生成中/成功/失败的报告均展示：报告页进入详情后自会轮询或重试，
     *   与历史面试页"查看报告"入口行为保持一致
     *
     * 性能取舍：
     * - 会话按"会话 ID IN 本页"一次性批量查询（一次查询替代 N+1），
     *   再在内存中按 sessionId 分组，仅用于补齐面试方向等摘要字段；
     *   缺失会话（异常数据）时方向字段缺省，不阻断列表返回
     *
     * @param userId 当前登录用户 ID
     * @param page   页码，从 1 开始（由调用方保证 >=1）
     * @param size   每页条数（由调用方保证 1~50）
     * @return 契约 InterviewReportPage（items 为轻量摘要，不含题目/回答/参考答案详情）
     */
    @Transactional(readOnly = true)
    public com.smartview.generated.web.model.InterviewReportPage listReports(Long userId, int page, int size) {
        Page<com.smartview.report.entity.InterviewReport> result = reportMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<com.smartview.report.entity.InterviewReport>()
                        .eq(com.smartview.report.entity.InterviewReport::getUserId, userId)
                        .orderByDesc(com.smartview.report.entity.InterviewReport::getCreatedAt));

        // 批量查询本页报告对应会话，避免逐条查询会话的 N+1 问题
        List<Long> sessionIds = result.getRecords().stream()
                .map(com.smartview.report.entity.InterviewReport::getSessionId)
                .toList();
        Map<Long, InterviewSession> sessionById = sessionIds.isEmpty() ? Map.of()
                : sessionMapper.selectBatchIds(sessionIds).stream()
                        .collect(Collectors.toMap(
                                InterviewSession::getId,
                                session -> session,
                                // 同一会话仅一份有效报告（唯一索引兜底），冲突时保留先出现的
                                (existing, replacement) -> existing));

        List<com.smartview.generated.web.model.InterviewReportSummary> items = result.getRecords().stream()
                .map(report -> toSummary(report, sessionById.get(report.getSessionId())))
                .toList();

        log.info("报告历史列表返回，userId={}, total={}, returned={}",
                userId, result.getTotal(), items.size());
        return new com.smartview.generated.web.model.InterviewReportPage(items, page, size, result.getTotal());
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

    /**
     * 报告实体 → 契约 InterviewReportSummary（列表专用轻量模型）。
     * 仅映射列表展示所需字段，避免加载题目/回答/参考答案等详情；
     * 会话缺失（异常数据）时方向字段缺省为 null，不阻断列表返回。
     */
    private com.smartview.generated.web.model.InterviewReportSummary toSummary(
            com.smartview.report.entity.InterviewReport entity, InterviewSession session) {
        return new com.smartview.generated.web.model.InterviewReportSummary(
                entity.getId().toString(),
                entity.getSessionId().toString(),
                entity.getUserId().toString(),
                safeSummaryStatus(entity.getStatus()))
                .roleDirection(safeSummaryDirection(session == null ? null : session.getRoleDirection()))
                .overallScore(entity.getOverallScore())
                .readinessLevel(safeSummaryReadiness(entity.getReadinessLevel()))
                .roleFitScore(entity.getRoleFitScore())
                .summary(entity.getSummary())
                .generatedAt(toOffsetDateTime(entity.getGeneratedAt()))
                .createdAt(toOffsetDateTime(entity.getCreatedAt()));
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

    // ==================== 摘要模型安全枚举转换 ====================
    // 生成器为 InterviewReportSummary 生成独立的枚举类（与 InterviewReport 各自一套），
    // 必须分别转换；未知值返回 null（响应缺省该字段而非整体 500），与既有安全转换风格一致。

    private com.smartview.generated.web.model.InterviewReportSummary.StatusEnum safeSummaryStatus(String code) {
        if (code == null) return null;
        try {
            return com.smartview.generated.web.model.InterviewReportSummary.StatusEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("报告状态值未知，响应缺省，status={}", code);
            return null;
        }
    }

    private com.smartview.generated.web.model.InterviewReportSummary.ReadinessLevelEnum safeSummaryReadiness(String code) {
        if (code == null) return null;
        try {
            return com.smartview.generated.web.model.InterviewReportSummary.ReadinessLevelEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("准备度等级值未知，响应缺省，level={}", code);
            return null;
        }
    }

    private com.smartview.generated.web.model.InterviewReportSummary.RoleDirectionEnum safeSummaryDirection(String code) {
        if (code == null) return null;
        try {
            return com.smartview.generated.web.model.InterviewReportSummary.RoleDirectionEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("面试方向值未知，响应缺省，direction={}", code);
            return null;
        }
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
