package com.smartview.task.scheduler;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.report.service.ReportTaskService;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告生成任务补偿调度器测试。
 *
 * 覆盖：扫描命中 FAILED/在途过期任务并逐一交给 ReportTaskService 重建、
 * 单条补偿异常记 skipped 不中断循环、无可恢复任务时零动作。
 */
@ExtendWith(MockitoExtension.class)
class ReportGenerateRetrySchedulerTest {

    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private ReportTaskService reportTaskService;

    private ResumeProperties resumeProperties;
    private ReportGenerateRetryScheduler scheduler;

    /**
     * 初始化 AiTask 表元数据（MyBatis-Plus 缓存），供调度器 LambdaQueryWrapper 的
     * 列名解析（.eq/.in 是急切的）；沿用 ReportTaskServiceTest 既有约定。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiTask.class);
    }

    @BeforeEach
    void setUp() {
        resumeProperties = new ResumeProperties();
        scheduler = new ReportGenerateRetryScheduler(
                aiTaskMapper, reportTaskService, resumeProperties);
    }

    @Test
    void retryStuckReportTasks_compensatesFailedAndStaleInFlightTasks() {
        AiTask failed = reportTask(TaskStatus.FAILED, "t-failed");
        AiTask stalePending = reportTask(TaskStatus.PENDING, "t-stale");
        stalePending.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(failed, stalePending));
        when(reportTaskService.compensateReportTask(any())).thenReturn(true);

        scheduler.retryStuckReportTasks();

        // 每个可恢复任务都交给 ReportTaskService 重建补偿任务。
        verify(reportTaskService).compensateReportTask(failed);
        verify(reportTaskService).compensateReportTask(stalePending);

        // 扫描条件必须覆盖 FAILED（finished_at IS NULL 租约）与在途任务（updated_at 租约），
        // 退休后旧任务 finished_at 已置位不再被下一轮扫描选中（列名在 SQL 段，取值在参数表）。
        ArgumentCaptor<LambdaQueryWrapper<AiTask>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiTaskMapper).selectList(wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sql)
                .contains("task_type")
                .contains("biz_type")
                .contains("task_status")
                .contains("updated_at")
                .contains("finished_at")
                .contains("ORDER BY created_at ASC")
                .contains("LIMIT 100");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
                .contains(TaskType.REPORT_GENERATE.getCode())
                .contains(BizType.INTERVIEW_SESSION.getCode())
                .contains(TaskStatus.FAILED.getCode())
                .contains(TaskStatus.RETRYING.getCode());
    }

    @Test
    void queryRecoverableTasks_excludesFailedTasksWithFinishedAtSet() {
        // 租约生效：FAILED 且 finished_at 已置位（已退休/已终态）的任务不应被扫描选中，
        // 否则退休后的旧任务在卡死场景下被反复选中、无界重建。selectList 为 Mockito mock，
        // 查询谓词即为权威，故以 SQL 含 finished_at IS NULL 租约作为验证点。
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());

        scheduler.retryStuckReportTasks();

        ArgumentCaptor<LambdaQueryWrapper<AiTask>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiTaskMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).contains("finished_at");
    }

    @Test
    void retryStuckReportTasks_continuesWhenCompensateThrows() {
        AiTask failed1 = reportTask(TaskStatus.FAILED, "t-failed-1");
        AiTask failed2 = reportTask(TaskStatus.FAILED, "t-failed-2");
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(failed1, failed2));
        doThrow(new IllegalStateException("补偿失败"))
                .when(reportTaskService).compensateReportTask(any());

        scheduler.retryStuckReportTasks();

        // 单条补偿异常只计数跳过，不中断整个扫描循环，全部任务都被尝试。
        verify(reportTaskService, times(2)).compensateReportTask(any());
    }

    @Test
    void retryStuckReportTasks_doesNothingWhenNoRecoverableTasks() {
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());

        scheduler.retryStuckReportTasks();

        verify(reportTaskService, never()).compensateReportTask(any());
    }

    private AiTask reportTask(TaskStatus status, String taskId) {
        return AiTask.builder()
                .taskId(taskId)
                .userId(7L)
                .taskType(TaskType.REPORT_GENERATE.getCode())
                .taskStatus(status.getCode())
                .bizType(BizType.INTERVIEW_SESSION.getCode())
                .bizId(88L)
                .traceId("trace")
                .retryCount(0)
                .maxRetry(3)
                .createdAt(LocalDateTime.now().minusMinutes(30))
                .build();
    }
}
