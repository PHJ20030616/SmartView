package com.smartview.observability;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartview.generated.web.model.LlmCallPage;
import com.smartview.generated.web.model.LlmCallSummary;
import com.smartview.observability.entity.LlmCallLog;
import com.smartview.observability.mapper.LlmCallLogMapper;
import com.smartview.observability.service.LlmCallLogQueryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 调用记录查询服务测试。
 *
 * 覆盖三件容易写错且后果明确的事：
 * 1. 过滤条件与排序确实落到 SQL（漏掉过滤会让看板展示错误的数据集）；
 * 2. 统计覆盖全部匹配记录而非当前页（否则成功率与成本会随翻页跳动）；
 * 3. P95 的 OFFSET 计算与边界收敛（只有 1 条记录时不能越过结果集）。
 */
@ExtendWith(MockitoExtension.class)
class LlmCallLogQueryServiceTest {

    @Mock
    private LlmCallLogMapper llmCallLogMapper;

    private LlmCallLogQueryService service;

    /**
     * 初始化 LlmCallLog 的 TableInfo（Lambda 缓存）。
     *
     * 纯 Mockito 单测没有 MyBatis-Plus 启动流程，而 LambdaQueryWrapper 的列名解析
     * 依赖 TableInfoHelper 的缓存（getSqlSegment 触发），与 InterviewSessionServiceTest 一致。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, LlmCallLog.class);
    }

    @BeforeEach
    void setUp() {
        // @Mock 注入发生在测试实例创建之后，服务必须在注入完成后构造
        service = new LlmCallLogQueryService(llmCallLogMapper);
    }

    private LlmCallLog sampleLog() {
        return LlmCallLog.builder()
                .id(88L)
                .traceId("00000000-0000-0000-0000-0000000000aa")
                .scene("evaluate")
                .provider("deepseek")
                .model("deepseek-v4-flash")
                .promptVersion("p0")
                .requestHash("a".repeat(64))
                .latencyMs(1234)
                .status("SUCCESS")
                .tokenInput(100)
                .tokenOutput(200)
                .tokenTotal(300)
                .temperature(new BigDecimal("0.10"))
                .maxTokens(4096)
                .retryAttempt(1)
                .createdAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .build();
    }

    @Test
    void mapsRecordsAndAggregatesStatsAcrossAllMatches() {
        Page<LlmCallLog> page = new Page<>(1, 20);
        page.setRecords(List.of(sampleLog()));
        page.setTotal(3L);
        when(llmCallLogMapper.selectPage(any(Page.class), any())).thenReturn(page);
        // 统计口径是"全部匹配记录"，因此 totalCalls=3 而非当前页的 1 条
        when(llmCallLogMapper.selectStats(any(), any(), any(), any()))
                .thenReturn(Map.of("totalCalls", 3L, "successCalls", 2L, "totalTokens", 900L));
        when(llmCallLogMapper.selectLatencyAtOffset(any(), any(), any(), any(), eq(2)))
                .thenReturn(1500);

        LlmCallPage result = service.listCalls("evaluate", "p0", null, null, 1, 20);

        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotal()).isEqualTo(3L);

        LlmCallSummary item = result.getItems().get(0);
        assertThat(item.getId()).isEqualTo("88");
        assertThat(item.getScene()).isEqualTo(LlmCallSummary.SceneEnum.EVALUATE);
        assertThat(item.getModel()).isEqualTo("deepseek-v4-flash");
        assertThat(item.getStatus()).isEqualTo(LlmCallSummary.StatusEnum.SUCCESS);
        assertThat(item.getLatencyMs()).isEqualTo(1234);
        assertThat(item.getTokenTotal()).isEqualTo(300);
        assertThat(item.getRetryAttempt()).isEqualTo(1);
        assertThat(item.getTraceId()).isEqualTo("00000000-0000-0000-0000-0000000000aa");
        // 库内 LocalDateTime 需按服务器时区转成契约要求的 date-time
        assertThat(item.getCreatedAt()).isNotNull();

        assertThat(result.getStats().getTotalCalls()).isEqualTo(3L);
        assertThat(result.getStats().getSuccessCalls()).isEqualTo(2L);
        assertThat(result.getStats().getTotalTokens()).isEqualTo(900L);
        // 2/3 保留四位小数（HALF_UP）
        assertThat(result.getStats().getSuccessRate()).isEqualTo(0.6667d);
        assertThat(result.getStats().getP95LatencyMs()).isEqualTo(1500);
    }

    @Test
    void appliesFiltersAndStableOrderingToQuery() {
        Page<LlmCallLog> empty = new Page<>(1, 20);
        empty.setRecords(List.of());
        empty.setTotal(0L);
        when(llmCallLogMapper.selectPage(any(Page.class), any())).thenReturn(empty);
        when(llmCallLogMapper.selectStats(any(), any(), any(), any())).thenReturn(null);

        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 30, 0, 0);
        service.listCalls("evaluate", "p0", from, to, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<LlmCallLog>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(llmCallLogMapper).selectPage(any(Page.class), captor.capture());
        LambdaQueryWrapper<LlmCallLog> wrapper = captor.getValue();

        assertThat(wrapper.getSqlSegment())
                .contains("scene")
                .contains("prompt_version")
                .contains("created_at")
                // 同一毫秒内的多条记录靠主键兜底，保证翻页结果稳定不重复
                .contains("ORDER BY created_at DESC,id DESC");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains("evaluate", "p0", from, to);

        // 列表与统计必须使用同一组过滤条件，否则看板数字与列表对不上
        verify(llmCallLogMapper).selectStats("evaluate", "p0", from, to);
    }

    @Test
    void blankFiltersAreNotApplied() {
        Page<LlmCallLog> empty = new Page<>(1, 20);
        empty.setRecords(List.of());
        empty.setTotal(0L);
        when(llmCallLogMapper.selectPage(any(Page.class), any())).thenReturn(empty);
        when(llmCallLogMapper.selectStats(any(), any(), any(), any())).thenReturn(null);

        service.listCalls("", "", null, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<LlmCallLog>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(llmCallLogMapper).selectPage(any(Page.class), captor.capture());

        // 空串必须被当作"不过滤"：否则看板不带条件时反而查不到任何记录
        assertThat(captor.getValue().getParamNameValuePairs()).isEmpty();
    }

    @Test
    void emptyResultSkipsP95QueryAndReturnsZeroStats() {
        Page<LlmCallLog> empty = new Page<>(2, 20);
        empty.setRecords(List.of());
        empty.setTotal(0L);
        when(llmCallLogMapper.selectPage(any(Page.class), any())).thenReturn(empty);
        when(llmCallLogMapper.selectStats(any(), any(), any(), any()))
                .thenReturn(Map.of("totalCalls", 0L, "successCalls", 0L, "totalTokens", 0L));

        LlmCallPage result = service.listCalls(null, null, null, null, 2, 20);

        assertThat(result.getStats().getSuccessRate()).isZero();
        assertThat(result.getStats().getP95LatencyMs()).isZero();
        // 无记录时不得再打一次数据库（offset 计算依赖 totalCalls）
        verify(llmCallLogMapper, never()).selectLatencyAtOffset(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void singleRecordClampsP95OffsetToZero() {
        Page<LlmCallLog> page = new Page<>(1, 20);
        page.setRecords(List.of());
        page.setTotal(1L);
        when(llmCallLogMapper.selectPage(any(Page.class), any())).thenReturn(page);
        when(llmCallLogMapper.selectStats(any(), any(), any(), any()))
                .thenReturn(Map.of("totalCalls", 1L, "successCalls", 1L, "totalTokens", 10L));
        when(llmCallLogMapper.selectLatencyAtOffset(isNull(), isNull(), isNull(), isNull(), eq(0)))
                .thenReturn(800);

        LlmCallPage result = service.listCalls(null, null, null, null, 1, 20);

        // floor(1 * 0.95) = 0，且上界收敛到 totalCalls-1 = 0，不会越过只有一条的结果集
        verify(llmCallLogMapper).selectLatencyAtOffset(null, null, null, null, 0);
        assertThat(result.getStats().getP95LatencyMs()).isEqualTo(800);
        assertThat(result.getStats().getSuccessRate()).isEqualTo(1.0d);
    }
}
