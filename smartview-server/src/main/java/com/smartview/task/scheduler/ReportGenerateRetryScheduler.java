package com.smartview.task.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.report.service.ReportTaskService;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报告生成任务补偿调度器。
 *
 * <p>会话进入 REPORTING 后报告任务经 afterCommit 投递 MQ，进程可能在提交与投递之间退出，
 * 或 MQ 投递失败被 markDispatchFailed 收口为 FAILED：此时会话停留在 REPORTING、报告停留
 * GENERATING，无任何下游可推进。此调度器以 ai_task 为权威，扫描这类"卡住"的
 * REPORT_GENERATE 任务，交给 ReportTaskService 重建新 taskId 补偿任务并重投 MQ。
 * 任务重建的幂等与并发安全由 compensateReportTask 的条件更新退休保证。</p>
 */
@Slf4j
@Component
public class ReportGenerateRetryScheduler {

    private final AiTaskMapper aiTaskMapper;
    private final ReportTaskService reportTaskService;
    private final ResumeProperties resumeProperties;

    public ReportGenerateRetryScheduler(
            AiTaskMapper aiTaskMapper,
            ReportTaskService reportTaskService,
            ResumeProperties resumeProperties) {
        this.aiTaskMapper = aiTaskMapper;
        this.reportTaskService = reportTaskService;
        this.resumeProperties = resumeProperties;
    }

    /**
     * 补偿扫描：找出投递失败（FAILED）或超过租约仍在途（PENDING/PROCESSING/RETRYING 且
     * updated_at 早于阈值）的 REPORT_GENERATE 任务，交给 ReportTaskService 重建补偿任务。
     */
    @Scheduled(
            fixedDelayString = "#{${smartview.resume.mq.scheduled-retry-interval-minutes:5} * 60 * 1000}",
            initialDelayString = "60000")
    public void retryStuckReportTasks() {
        LocalDateTime staleCutoff = calculateStaleCutoff();
        List<AiTask> tasks = queryRecoverableTasks(staleCutoff);
        if (tasks.isEmpty()) {
            return;
        }
        int recovered = 0;
        int skipped = 0;
        for (AiTask task : tasks) {
            try {
                reportTaskService.compensateReportTask(task);
                recovered++;
            } catch (Exception exception) {
                skipped++;
                log.error("报告任务补偿异常，taskId={}", task.getTaskId(), exception);
            }
        }
        log.info("报告任务补偿扫描完成，可恢复={}, 已补偿={}, 异常跳过={}",
                tasks.size(), recovered, skipped);
    }

    private List<AiTask> queryRecoverableTasks(LocalDateTime staleCutoff) {
        return aiTaskMapper.selectList(new LambdaQueryWrapper<AiTask>()
                .eq(AiTask::getTaskType, TaskType.REPORT_GENERATE.getCode())
                .eq(AiTask::getBizType, BizType.INTERVIEW_SESSION.getCode())
                .and(wrapper -> wrapper
                        .eq(AiTask::getTaskStatus, TaskStatus.FAILED.getCode())
                        .or(nested -> nested
                                .in(AiTask::getTaskStatus,
                                        TaskStatus.PENDING.getCode(),
                                        TaskStatus.PROCESSING.getCode(),
                                        TaskStatus.RETRYING.getCode())
                                .apply("(updated_at IS NULL OR updated_at <= {0})", staleCutoff)))
                .orderByAsc(AiTask::getCreatedAt)
                .last("LIMIT 100"));
    }

    private LocalDateTime calculateStaleCutoff() {
        Integer intervalMinutes = resumeProperties.getMq().getScheduledRetryIntervalMinutes();
        long safe = intervalMinutes == null ? 5L : Math.max(1L, intervalMinutes);
        return LocalDateTime.now().minusMinutes(safe);
    }
}
