package com.smartview.cleanup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 清理任务补偿调度器（Task 7.2 清理失败可重试）。
 *
 * <p>软删除事务提交后，MQ 投递发生在 afterCommit 回调中，进程可能在两者之间退出；
 * worker 发布结果失败时，原任务也可能进入任务 DLQ。此调度器以 ai_task 为权威，
 * 通过“状态 + retry_count + updated_at”条件抢占任务，避免多实例重复投递。</p>
 *
 * <p>清理目标是可重试的派生数据：MinIO 对象与 Chroma 向量。只要未到最大重试次数，
 * 调度器就会根据 request_payload_json 重建消息并重新投递；重试耗尽后标记最终 FAILED，
 * 保留 error_message 供运维审计，不再无意义重试。</p>
 */
@Slf4j
@Component
public class CleanupRetryScheduler {

    private final AiTaskMapper aiTaskMapper;
    private final CleanupTaskProducer producer;
    private final CleanupTaskService cleanupTaskService;
    private final ResumeProperties resumeProperties;

    public CleanupRetryScheduler(
            AiTaskMapper aiTaskMapper,
            CleanupTaskProducer producer,
            CleanupTaskService cleanupTaskService,
            ResumeProperties resumeProperties) {
        this.aiTaskMapper = aiTaskMapper;
        this.producer = producer;
        this.cleanupTaskService = cleanupTaskService;
        this.resumeProperties = resumeProperties;
    }

    /**
     * 定时扫描可重试的清理任务并重新投递；无任务时立即返回。
     */
    @Scheduled(
            fixedDelayString = "#{${smartview.resume.mq.scheduled-retry-interval-minutes:5} * 60 * 1000}",
            initialDelayString = "60000")
    public void retryFailedCleanupTasks() {
        LocalDateTime staleCutoff = calculateStaleCutoff();
        List<AiTask> tasks = queryRetryableTasks(staleCutoff);
        if (tasks.isEmpty()) {
            return;
        }

        int sentCount = 0;
        int failedCount = 0;
        for (AiTask task : tasks) {
            // 每个任务按自己的 traceId 建立作用域：调度线程长期存活，逐任务切换才能让
            // 重投出的消息与日志对应到具体任务，而不是整轮共用一个残留 ID。
            try (TraceIdContext.Scope ignored =
                         TraceIdContext.scope(TraceIdContext.resolveTraceId(task.getTraceId()))) {
                try {
                    if (retryTask(task, staleCutoff)) {
                        sentCount++;
                    } else {
                        failedCount++;
                    }
                } catch (Exception exception) {
                    failedCount++;
                    log.error("清理任务补偿异常，taskId={}", task.getTaskId(), exception);
                }
            }
        }
        log.info("清理任务补偿完成，总数={}, 已投递={}, 未处理={}",
                tasks.size(), sentCount, failedCount);
    }

    private List<AiTask> queryRetryableTasks(LocalDateTime staleCutoff) {
        return aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.CLEANUP.getCode())
                        .apply("(task_status = {0} OR " +
                                        "(task_status IN ({1}, {2}) AND " +
                                        "(updated_at IS NULL OR updated_at <= {3})))",
                                TaskStatus.FAILED.getCode(),
                                TaskStatus.RETRYING.getCode(),
                                TaskStatus.PENDING.getCode(),
                                staleCutoff)
                        .apply("finished_at IS NULL")
                        .orderByAsc(AiTask::getCreatedAt)
                        .last("LIMIT 100"));
    }

    private boolean retryTask(AiTask task, LocalDateTime staleCutoff) {
        int maxRetry = task.getMaxRetry() == null
                ? resumeProperties.getMq().getMaxScheduledRetryCount()
                : task.getMaxRetry();
        int currentRetry = task.getRetryCount() == null ? 0 : task.getRetryCount();

        if (currentRetry >= maxRetry) {
            return markFinalFailed(task.getTaskId(), currentRetry, staleCutoff,
                    "清理任务超过最大补偿次数");
        }
        if (task.getBizId() == null || task.getTraceId() == null || task.getTaskId() == null) {
            return markFinalFailed(task.getTaskId(), currentRetry, staleCutoff,
                    "清理任务缺少服务端生成的关联字段");
        }
        // 重建消息；requestPayloadJson 缺失说明任务创建时就未落库清理目标，无法安全重试。
        // objectKey 是硬性要求；画像 ID 列表允许为空（解析失败的简历仅清理 MinIO 文件）。
        CleanupMessage message = cleanupTaskService.rebuildMessage(task);
        if (message.getObjectKey() == null || message.getObjectKey().isBlank()) {
            return markFinalFailed(task.getTaskId(), currentRetry, staleCutoff,
                    "清理任务缺少对象存储 Key");
        }

        int nextRetry = currentRetry + 1;
        int claimed = aiTaskMapper.update(
                null,
                new UpdateWrapper<AiTask>()
                        .eq("task_id", task.getTaskId())
                        .apply("(task_status = {0} OR " +
                                        "(task_status IN ({1}, {2}) AND " +
                                        "(updated_at IS NULL OR updated_at <= {3})))",
                                TaskStatus.FAILED.getCode(),
                                TaskStatus.RETRYING.getCode(),
                                TaskStatus.PENDING.getCode(),
                                staleCutoff)
                        .apply("((retry_count IS NULL AND {0} = 0) OR retry_count = {0})",
                                currentRetry)
                        .apply("finished_at IS NULL")
                        .set("task_status", TaskStatus.RETRYING.getCode())
                        .set("retry_count", nextRetry)
                        .set("error_message", null)
                        .set("finished_at", null)
                        .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) {
            return false;
        }

        try {
            message.setRetryCount(nextRetry);
            producer.send(message);
            return true;
        } catch (Exception exception) {
            // 保持 RETRYING 且不写 finished_at；下一轮会按 updated_at 租约再次抢占
            aiTaskMapper.update(
                    null,
                    new UpdateWrapper<AiTask>()
                            .eq("task_id", task.getTaskId())
                            .eq("retry_count", nextRetry)
                            .apply("finished_at IS NULL")
                            .set("error_message", "补偿投递失败：" + exception.getMessage())
                            .set("updated_at", LocalDateTime.now()));
            log.warn("清理任务补偿投递失败，taskId={}, retryCount={}",
                    task.getTaskId(), nextRetry, exception);
            return false;
        }
    }

    private boolean markFinalFailed(
            String taskId,
            int currentRetry,
            LocalDateTime staleCutoff,
            String errorMessage) {
        if (taskId == null) {
            return false;
        }
        int updated = aiTaskMapper.update(
                null,
                new UpdateWrapper<AiTask>()
                        .eq("task_id", taskId)
                        .apply("(task_status = {0} OR " +
                                        "(task_status IN ({1}, {2}) AND " +
                                        "(updated_at IS NULL OR updated_at <= {3})))",
                                TaskStatus.FAILED.getCode(),
                                TaskStatus.RETRYING.getCode(),
                                TaskStatus.PENDING.getCode(),
                                staleCutoff)
                        .apply("((retry_count IS NULL AND {0} = 0) OR retry_count = {0})",
                                currentRetry)
                        .apply("finished_at IS NULL")
                        .set("task_status", TaskStatus.FAILED.getCode())
                        .set("error_message", errorMessage)
                        .set("finished_at", LocalDateTime.now())
                        .set("updated_at", LocalDateTime.now()));
        return updated > 0;
    }

    private LocalDateTime calculateStaleCutoff() {
        Integer intervalMinutes = resumeProperties.getMq().getScheduledRetryIntervalMinutes();
        long safeIntervalMinutes = intervalMinutes == null
                ? 5L
                : Math.max(1L, intervalMinutes);
        return LocalDateTime.now().minusMinutes(safeIntervalMinutes);
    }
}
