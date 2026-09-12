package com.smartview.observability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartview.generated.web.model.LlmCallPage;
import com.smartview.generated.web.model.LlmCallStats;
import com.smartview.generated.web.model.LlmCallSummary;
import com.smartview.observability.entity.LlmCallLog;
import com.smartview.observability.mapper.LlmCallLogMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用记录查询服务。
 *
 * 职责边界：只读。写入由 FastAPI 完成（plan_1.1 §5.4），后端不参与写入，
 * 因此这里没有事务注解，也没有任何 update/delete 路径。
 */
@Slf4j
@Service
public class LlmCallLogQueryService {

    /** P95 的分位点 */
    private static final double P95 = 0.95;

    private final LlmCallLogMapper llmCallLogMapper;

    public LlmCallLogQueryService(LlmCallLogMapper llmCallLogMapper) {
        this.llmCallLogMapper = llmCallLogMapper;
    }

    /**
     * 分页查询调用记录，并附带过滤条件下的整体统计。
     *
     * 统计与列表使用同一组过滤条件，但统计覆盖全部匹配记录而非当前页——
     * 看板上的成功率与成本不应该随翻页跳动。
     */
    public LlmCallPage listCalls(String scene, String promptVersion,
                                 LocalDateTime from, LocalDateTime to,
                                 int page, int size) {
        Page<LlmCallLog> pageRequest = new Page<>(page, size);
        LambdaQueryWrapper<LlmCallLog> wrapper = new LambdaQueryWrapper<LlmCallLog>()
                .eq(StringUtils.hasText(scene), LlmCallLog::getScene, scene)
                .eq(StringUtils.hasText(promptVersion), LlmCallLog::getPromptVersion, promptVersion)
                .ge(from != null, LlmCallLog::getCreatedAt, from)
                .lt(to != null, LlmCallLog::getCreatedAt, to)
                // 同一毫秒内的多条记录靠主键兜底排序，保证翻页结果稳定不重复
                .orderByDesc(LlmCallLog::getCreatedAt)
                .orderByDesc(LlmCallLog::getId);

        Page<LlmCallLog> result = llmCallLogMapper.selectPage(pageRequest, wrapper);
        log.info("查询 LLM 调用记录，scene={}, promptVersion={}, page={}, size={}, total={}",
                scene, promptVersion, page, size, result.getTotal());

        LlmCallPage response = new LlmCallPage();
        response.setItems(toSummaries(result.getRecords()));
        response.setPage((int) result.getCurrent());
        response.setSize((int) result.getSize());
        response.setTotal(result.getTotal());
        response.setStats(buildStats(scene, promptVersion, from, to));
        return response;
    }

    private List<LlmCallSummary> toSummaries(List<LlmCallLog> logs) {
        return logs.stream().map(this::toSummary).toList();
    }

    private LlmCallSummary toSummary(LlmCallLog log) {
        LlmCallSummary summary = new LlmCallSummary();
        summary.setId(String.valueOf(log.getId()));
        summary.setTraceId(log.getTraceId());
        summary.setScene(safeScene(log.getScene()));
        summary.setProvider(log.getProvider());
        summary.setModel(log.getModel());
        summary.setPromptKey(log.getPromptKey());
        summary.setPromptVersion(log.getPromptVersion());
        summary.setStatus(safeStatus(log.getStatus()));
        summary.setErrorCode(log.getErrorCode());
        summary.setLatencyMs(log.getLatencyMs());
        summary.setTokenInput(log.getTokenInput());
        summary.setTokenOutput(log.getTokenOutput());
        summary.setTokenTotal(log.getTokenTotal());
        summary.setRetryAttempt(log.getRetryAttempt());
        summary.setRequestHash(log.getRequestHash());
        summary.setCreatedAt(toOffsetDateTime(log.getCreatedAt()));
        return summary;
    }

    // ==================== 安全枚举/时间转换 ====================

    /**
     * 场景值转契约枚举；未知值只告警并置空，不让整页查询失败。
     *
     * 与 ReportQueryService 的 safeXxx 系列保持一致：观测表里的历史数据可能由更早版本的
     * 调用方写入，出现未知 scene 时看板应降级展示而不是 500。
     */
    private LlmCallSummary.SceneEnum safeScene(String code) {
        if (code == null) {
            return null;
        }
        try {
            return LlmCallSummary.SceneEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("LLM 调用场景值未知，响应缺省，scene={}", code);
            return null;
        }
    }

    /** 调用结果转契约枚举；未知值只告警并置空，理由同 {@link #safeScene(String)}。 */
    private LlmCallSummary.StatusEnum safeStatus(String code) {
        if (code == null) {
            return null;
        }
        try {
            return LlmCallSummary.StatusEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            log.warn("LLM 调用结果值未知，响应缺省，status={}", code);
            return null;
        }
    }

    /** 库内时间按服务器时区转 OffsetDateTime，与契约的 date-time 字段对齐。 */
    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /**
     * 组装汇总统计。
     *
     * P95 用"排序 + OFFSET"单独取一条记录，而不是在 SQL 里做分位计算：
     * MySQL 8 没有 PERCENTILE_CONT，而把全表延迟拉回内存再算在数据量上来后不可接受。
     */
    private LlmCallStats buildStats(String scene, String promptVersion,
                                    LocalDateTime from, LocalDateTime to) {
        Map<String, Object> row = llmCallLogMapper.selectStats(scene, promptVersion, from, to);
        long totalCalls = toLong(row == null ? null : row.get("totalCalls"));
        long successCalls = toLong(row == null ? null : row.get("successCalls"));
        long totalTokens = toLong(row == null ? null : row.get("totalTokens"));

        LlmCallStats stats = new LlmCallStats();
        stats.setTotalCalls(totalCalls);
        stats.setSuccessCalls(successCalls);
        stats.setTotalTokens(totalTokens);
        stats.setSuccessRate(calculateSuccessRate(totalCalls, successCalls));
        stats.setP95LatencyMs(resolveP95Latency(scene, promptVersion, from, to, totalCalls));
        return stats;
    }

    private Double calculateSuccessRate(long totalCalls, long successCalls) {
        if (totalCalls <= 0) {
            return 0.0d;
        }
        return BigDecimal.valueOf(successCalls)
                .divide(BigDecimal.valueOf(totalCalls), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private Integer resolveP95Latency(String scene, String promptVersion,
                                      LocalDateTime from, LocalDateTime to, long totalCalls) {
        if (totalCalls <= 0) {
            return 0;
        }
        // offset 上界收敛到 totalCalls-1，避免只有 1 条记录时越过结果集
        int offset = (int) Math.min(
                Math.floor(totalCalls * P95),
                totalCalls - 1
        );
        Integer latency = llmCallLogMapper.selectLatencyAtOffset(
                scene, promptVersion, from, to, offset);
        return latency == null ? 0 : latency;
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
