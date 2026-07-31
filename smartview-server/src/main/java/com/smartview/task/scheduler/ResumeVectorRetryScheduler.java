package com.smartview.task.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeVectorTaskProducer;
import com.smartview.task.mq.ResumeVectorizeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历向量任务补偿调度器。
 *
 * <p>确认事务提交后，MQ 投递发生在 afterCommit 回调中，进程可能在两者之间退出；
 * worker 发布结果失败时，原任务也可能进入任务 DLQ。此调度器以 ai_task 为权威，
 * 通过“状态 + retry_count + updated_at”条件抢占任务，避免多实例重复投递。</p>
 */
@Slf4j
@Component
public class ResumeVectorRetryScheduler {

    private final AiTaskMapper aiTaskMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final ResumeVectorTaskProducer producer;
    private final ResumeProperties resumeProperties;

    public ResumeVectorRetryScheduler(
            AiTaskMapper aiTaskMapper,
            ResumeProfileMapper resumeProfileMapper,
            ResumeVectorTaskProducer producer,
            ResumeProperties resumeProperties) {
        this.aiTaskMapper = aiTaskMapper;
        this.resumeProfileMapper = resumeProfileMapper;
        this.producer = producer;
        this.resumeProperties = resumeProperties;
    }

    @Scheduled(
            fixedDelayString = "#{${smartview.resume.mq.scheduled-retry-interval-minutes:5} * 60 * 1000}",
            initialDelayString = "60000")
    public void retryFailedVectorTasks() {
        LocalDateTime staleCutoff = calculateStaleCutoff();
        List<AiTask> tasks = queryRetryableTasks(staleCutoff);
        if (tasks.isEmpty()) {
            return;
        }

        int sentCount = 0;
        int failedCount = 0;
        for (AiTask task : tasks) {
            try {
                if (retryTask(task, staleCutoff)) {
                    sentCount++;
                } else {
                    failedCount++;
                }
            } catch (Exception exception) {
                failedCount++;
                log.error("向量任务补偿异常，taskId={}", task.getTaskId(), exception);
            }
        }
        log.info("简历向量任务补偿完成，总数={}, 已投递={}, 未处理={}",
                tasks.size(), sentCount, failedCount);
    }

    private List<AiTask> queryRetryableTasks(LocalDateTime staleCutoff) {
        return aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.RESUME_VECTORIZE.getCode())
                        .eq(AiTask::getBizType, BizType.RESUME_PROFILE.getCode())
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
                    "向量入库任务超过最大补偿次数");
        }

        String operation = task.getOperation() == null ? "UPSERT" : task.getOperation();
        if ("UPSERT".equals(operation) && !isCurrentConfirmedProfile(task)) {
            return markFinalFailed(task.getTaskId(), currentRetry, staleCutoff,
                    "向量任务关联的简历画像已不存在、未确认或版本已过期");
        }
        if (task.getBizId() == null || task.getProfileVersion() == null
                || task.getTraceId() == null || task.getTaskId() == null) {
            return markFinalFailed(task.getTaskId(), currentRetry, staleCutoff,
                    "向量任务缺少服务端生成的关联字段");
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

        ResumeVectorizeMessage message = ResumeVectorizeMessage.builder()
                .taskId(task.getTaskId())
                .traceId(task.getTraceId())
                .messageType(task.getMessageType() == null
                        ? "RESUME_VECTORIZE_TASK" : task.getMessageType())
                .schemaVersion(task.getSchemaVersion() == null
                        ? "1.0.0" : task.getSchemaVersion())
                .retryCount(nextRetry)
                .createdAt(task.getCreatedAt() == null
                        ? LocalDateTime.now() : task.getCreatedAt())
                .resumeProfileId(String.valueOf(task.getBizId()))
                .profileVersion(task.getProfileVersion())
                .operation(operation)
                .build();
        try {
            producer.send(message);
            return true;
        } catch (Exception exception) {
            // 保持 RETRYING 且不写 finished_at；下一轮会按 updated_at 租约再次抢占。
            aiTaskMapper.update(
                    null,
                    new UpdateWrapper<AiTask>()
                            .eq("task_id", task.getTaskId())
                            .eq("retry_count", nextRetry)
                            .apply("finished_at IS NULL")
                            .set("error_message", "补偿投递失败：" + exception.getMessage())
                            .set("updated_at", LocalDateTime.now()));
            log.warn("向量任务补偿投递失败，taskId={}, retryCount={}",
                    task.getTaskId(), nextRetry, exception);
            return false;
        }
    }

    private boolean isCurrentConfirmedProfile(AiTask task) {
        ResumeProfile profile = resumeProfileMapper.selectById(task.getBizId());
        return profile != null
                && ConfirmStatus.CONFIRMED.getCode().equals(profile.getConfirmStatus())
                && task.getProfileVersion() != null
                && task.getProfileVersion().equals(profile.getVersion());
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
