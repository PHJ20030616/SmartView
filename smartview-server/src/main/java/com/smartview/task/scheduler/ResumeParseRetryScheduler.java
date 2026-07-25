package com.smartview.task.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartview.common.enums.ParseStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.infra.minio.MinioService;
import com.smartview.resume.entity.ResumeFile;
import com.smartview.resume.mapper.ResumeFileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeParseMessage;
import com.smartview.task.mq.ResumeTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历解析任务重试调度器
 *
 * 功能说明：
 * - 定时扫描 FAILED 状态的简历解析任务
 * - 重新投递到 RabbitMQ，实现兜底重试机制
 * - 超过最大重试次数后标记为永久失败
 * - 作为上传接口立即重试的补充，处理极端情况
 *
 * 调度策略：
 * - 默认每 5 分钟执行一次（可配置）
 * - 每次最多处理 100 个失败任务（避免长时间占用线程）
 * - 重试次数上限默认 3 次（可配置）
 * - 超过重试次数后记录最终失败时间，使用 FAILED 状态停止调度
 *
 * 技术要点：
 * - @Scheduled 注解配置定时任务
 * - fixedDelayString 支持从配置文件读取间隔时间
 * - 使用 @Transactional 保证任务状态更新的原子性
 * - 失败任务按创建时间升序处理，优先处理早期任务
 *
 * 注意事项：
 * - 定时任务与上传接口的立即重试是互补关系，不是替代关系
 * - 定时任务主要处理网络抖动、MQ 服务重启等极端情况
 * - 大部分任务应该在上传接口的立即重试中成功，定时任务处理量应该很少
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Slf4j
@Component
public class ResumeParseRetryScheduler {

    private final AiTaskMapper aiTaskMapper;
    private final ResumeFileMapper resumeFileMapper;
    private final ResumeTaskProducer resumeTaskProducer;
    private final MinioService minioService;
    private final ResumeProperties resumeProperties;

    /**
     * 构造函数注入依赖
     */
    public ResumeParseRetryScheduler(
            AiTaskMapper aiTaskMapper,
            ResumeFileMapper resumeFileMapper,
            ResumeTaskProducer resumeTaskProducer,
            MinioService minioService,
            ResumeProperties resumeProperties
    ) {
        this.aiTaskMapper = aiTaskMapper;
        this.resumeFileMapper = resumeFileMapper;
        this.resumeTaskProducer = resumeTaskProducer;
        this.minioService = minioService;
        this.resumeProperties = resumeProperties;
    }

    /**
     * 定时重试失败的简历解析任务
     * 默认每 5 分钟执行一次，可通过配置文件调整
     *
     * fixedDelayString：上次任务结束后等待指定时间再执行下次任务（单位：毫秒）
     * initialDelayString：应用启动后延迟指定时间再首次执行（单位：毫秒）
     */
    @Scheduled(
            fixedDelayString = "#{${smartview.resume.mq.scheduled-retry-interval-minutes:5} * 60 * 1000}",
            initialDelayString = "60000"  // 启动后 1 分钟开始执行
    )
    public void retryFailedParseTasks() {
        log.info("开始执行简历解析任务重试调度");

        try {
            // 查询需要补偿的任务（未达到最大重试次数）
            /*
             * PENDING 任务可能是在数据库事务提交后、发送 MQ 前进程宕机留下的。
             * 使用同一时刻计算出的截止时间完成“查询 + 抢占”，避免查询后任务刚变新仍被错误抢占。
             */
            LocalDateTime stalePendingCutoff = calculateStalePendingCutoff();
            List<AiTask> failedTasks = queryFailedTasks(stalePendingCutoff);

            if (failedTasks.isEmpty()) {
                log.info("没有需要重试的失败任务");
                return;
            }

            log.info("找到 {} 个失败任务需要重试", failedTasks.size());

            int successCount = 0;
            int failedCount = 0;
            int permanentlyFailedCount = 0;

            // 逐个处理失败任务
            for (AiTask task : failedTasks) {
                try {
                    boolean success = retryTask(task, stalePendingCutoff);
                    if (success) {
                        successCount++;
                    } else {
                        // 关联文件已不存在时已被标记为最终失败，避免重复更新并保留原始错误原因。
                        if (task.getFinishedAt() != null) {
                            permanentlyFailedCount++;
                            continue;
                        }
                        // 检查是否达到最大重试次数，兼容历史数据中的空值。
                        if (!hasRetriesRemaining(task)) {
                            markAsPermanentlyFailed(task);
                            permanentlyFailedCount++;
                        } else {
                            failedCount++;
                        }
                    }
                } catch (Exception e) {
                    log.error("重试任务异常，taskId={}", task.getTaskId(), e);
                    failedCount++;
                }
            }

            log.info("简历解析任务重试调度完成，总计={}, 成功={}, 失败={}, 永久失败={}",
                    failedTasks.size(), successCount, failedCount, permanentlyFailedCount);

        } catch (Exception e) {
            log.error("简历解析任务重试调度异常", e);
        }
    }

    /**
     * 查询需要补偿的任务
     * 条件：
     * 1. 任务类型为 RESUME_PARSE
     * 2. 任务状态为 FAILED，或 RETRYING/PENDING 的租约已过期
     * 3. 重试次数小于最大重试次数且未写入结束时间
     * 4. 按创建时间升序排序
     * 5. 最多返回 100 个任务
     *
     * @return 失败任务列表
     */
    private List<AiTask> queryFailedTasks(LocalDateTime stalePendingCutoff) {
        return aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.RESUME_PARSE.getCode())
                        /*
                         * FAILED 任务可在下一轮调度，RETRYING/PENDING 只有租约超时后才能恢复。
                         * RETRYING 也必须受租约保护，否则多实例会在一个实例仍发送 MQ 时重复抢占。
                         */
                        .apply("(task_status = {0} OR " +
                                        "(task_status IN ({1}, {2}) AND " +
                                        "(updated_at IS NULL OR updated_at <= {3})))",
                                TaskStatus.FAILED.getCode(),
                                TaskStatus.RETRYING.getCode(),
                                TaskStatus.PENDING.getCode(),
                                stalePendingCutoff)
                        /*
                         * 使用任务自身的 max_retry，兼容历史 retry_count/max_retry 为空的数据。
                         * finished_at 非空表示已经被标记为最终失败，不应再次进入调度队列。
                         */
                        .apply("(finished_at IS NULL)")
                        .apply("(COALESCE(retry_count, 0) < COALESCE(max_retry, {0}))",
                                resumeProperties.getMq().getMaxScheduledRetryCount())
                        .orderByAsc(AiTask::getCreatedAt)
                        .last("LIMIT 100")
        );
    }

    /**
     * 重试单个任务
     *
     * @param task 失败的任务
     * @return true=重试成功，false=重试失败
     */
    private boolean retryTask(AiTask task, LocalDateTime stalePendingCutoff) {
        ResumeFile resumeFile = null;
        int claimedRetryCount = -1;
        try {
            AiTask currentTask = aiTaskMapper.selectOne(
                    new LambdaQueryWrapper<AiTask>()
                            .eq(AiTask::getTaskId, task.getTaskId()));
            if (currentTask != null) {
                copyTaskState(currentTask, task);
                if (isTaskInFlightOrSuccessful(currentTask.getTaskStatus())) {
                    // 消费者可能在调度器取数后先完成，不能重复投递或覆盖成功状态。
                    return true;
                }
            }

            int maxRetry = task.getMaxRetry() == null
                    ? resumeProperties.getMq().getMaxScheduledRetryCount()
                    : task.getMaxRetry();
            int currentRetryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
            int nextRetryCount = currentRetryCount + 1;
            if (currentRetryCount >= maxRetry) {
                task.setMaxRetry(maxRetry);
                task.setTaskStatus(TaskStatus.FAILED.getCode());
                task.setFinishedAt(task.getFinishedAt() == null ? LocalDateTime.now() : task.getFinishedAt());
                return false;
            }

            /*
             * 先原子占用任务，再操作对象存储和 MQ，避免多实例调度器同时重复投递同一任务。
             * 更新返回 0 表示任务已被其他实例处理，刷新状态后交给本轮统计。
             */
            int claimed = aiTaskMapper.update(
                            null,
                            new UpdateWrapper<AiTask>()
                                    .eq("task_id", task.getTaskId())
                                    // 使用原生条件兼容 MyBatis-Plus Lambda 缓存尚未初始化的测试和启动场景。
                                    .apply("(task_status = {0} OR " +
                                                    "(task_status IN ({1}, {2}) AND " +
                                                    "(updated_at IS NULL OR updated_at <= {3})))",
                                            TaskStatus.FAILED.getCode(),
                                            TaskStatus.RETRYING.getCode(),
                                            TaskStatus.PENDING.getCode(),
                                            stalePendingCutoff)
                                    .apply("((retry_count IS NULL AND {0} = 0) OR retry_count = {0})",
                                            currentRetryCount)
                                    .apply("(finished_at IS NULL)")
                                    .set("max_retry", maxRetry)
                                    .set("task_status", TaskStatus.RETRYING.getCode())
                                    .set("retry_count", nextRetryCount)
                                    .set("error_message", null)
                                    .set("finished_at", null)
                                    .set("updated_at", LocalDateTime.now())
            );
            if (claimed == 0) {
                AiTask latestTask = aiTaskMapper.selectOne(
                        new LambdaQueryWrapper<AiTask>()
                                .eq(AiTask::getTaskId, task.getTaskId()));
                if (latestTask != null) {
                    copyTaskState(latestTask, task);
                }
                return isTaskInFlightOrSuccessful(task.getTaskStatus());
            }
            // 后续所有异常回写都必须带上本次抢占生成的 retry_count，避免旧实例覆盖新实例。
            claimedRetryCount = nextRetryCount;

            // 查询关联的简历文件
            resumeFile = resumeFileMapper.selectById(task.getBizId());
            if (resumeFile == null) {
                log.error("简历文件不存在，taskId={}, resumeFileId={}", task.getTaskId(), task.getBizId());
                /*
                 * 关联业务数据已经不存在时，继续重试不会改变结果，只会让调度器每轮重复扫描同一任务。
                 * 将计数直接推进到上限并记录结束时间，保留 FAILED 作为数据库中已建模的最终失败状态。
                 */
                task.setMaxRetry(maxRetry);
                task.setRetryCount(Math.max(currentRetryCount, maxRetry));
                task.setTaskStatus(TaskStatus.FAILED.getCode());
                task.setErrorMessage("关联的简历文件不存在，无法继续重试");
                task.setFinishedAt(LocalDateTime.now());
                /*
                 * 这里不能使用旧实体 updateById：查询文件期间，结果消费者可能已经把任务更新为 SUCCESS。
                 * 绑定本次抢占的 RETRYING 状态和 retry_count，抢占丢失时放弃回写。
                 */
                int taskUpdated = aiTaskMapper.update(
                        null,
                        new UpdateWrapper<AiTask>()
                                .eq("task_id", task.getTaskId())
                                .eq("task_status", TaskStatus.RETRYING.getCode())
                                .eq("retry_count", nextRetryCount)
                                .isNull("finished_at")
                                .set("max_retry", maxRetry)
                                .set("retry_count", Math.max(currentRetryCount, maxRetry))
                                .set("task_status", TaskStatus.FAILED.getCode())
                                .set("error_message", task.getErrorMessage())
                                .set("finished_at", task.getFinishedAt())
                                .set("updated_at", LocalDateTime.now())
                );
                if (taskUpdated == 0) {
                    AiTask latestTask = aiTaskMapper.selectOne(
                            new LambdaQueryWrapper<AiTask>()
                                    .eq(AiTask::getTaskId, task.getTaskId()));
                    if (latestTask != null) {
                        copyTaskState(latestTask, task);
                    }
                    // 结果消费者或其他调度实例已接管时，不能继续修改关联数据。
                    return isTaskInFlightOrSuccessful(task.getTaskStatus());
                }
                return false;
            }

            /*
             * 先把任务标记为 RETRYING 再发送 MQ。发送成功后不再使用旧实体写回数据库，
             * 因此结果消费者可以安全地把同一任务更新为 SUCCESS；如果进程在发送前崩溃，
             * RETRYING 状态仍会被下一轮调度重新捞取。
             */
            task.setMaxRetry(maxRetry);
            task.setTaskStatus(TaskStatus.RETRYING.getCode());
            task.setRetryCount(nextRetryCount);
            task.setErrorMessage(null);
            task.setFinishedAt(null);
            resumeFile.setParseStatus(ParseStatus.PENDING.getCode());
            resumeFile.setErrorMessage(null);
            /*
             * 仅把仍未开始处理的文件标记为 PENDING。消费者可能已经并发写入 SUCCESS，
             * 因此不能再使用旧实体 updateById 覆盖数据库中的最新状态。
             */
            UpdateWrapper<ResumeFile> fileStatusUpdate = new UpdateWrapper<ResumeFile>()
                    .eq("id", resumeFile.getId())
                    .apply("parse_status NOT IN ({0}, {1})",
                            ParseStatus.SUCCESS.getCode(),
                            ParseStatus.PROCESSING.getCode())
                    .set("parse_status", ParseStatus.PENDING.getCode())
                    .set("error_message", null)
                    .set("updated_at", LocalDateTime.now());
            addCurrentLeaseCondition(fileStatusUpdate, task.getTaskId(), claimedRetryCount);
            int fileUpdated = resumeFileMapper.update(null, fileStatusUpdate);
            if (fileUpdated == 0) {
                ResumeFile latestResumeFile = resumeFileMapper.selectById(resumeFile.getId());
                if (latestResumeFile != null
                        && (ParseStatus.SUCCESS.getCode().equals(latestResumeFile.getParseStatus())
                        || ParseStatus.PROCESSING.getCode().equals(latestResumeFile.getParseStatus()))) {
                    // 文件已经被消费者推进，重复投递没有收益，也不能覆盖其最新状态。
                    return true;
                }
            }

            /*
             * 对象存储和 MQ 操作可能耗时较长。发送前原子续租一次，确认本实例仍持有
             * 当前 retry_count 的任务；续租失败时立即停止，避免旧实例继续发送或回写文件。
             */
            int leaseRenewed = aiTaskMapper.update(
                    null,
                    new UpdateWrapper<AiTask>()
                            .eq("task_id", task.getTaskId())
                            .eq("task_status", TaskStatus.RETRYING.getCode())
                            .eq("retry_count", claimedRetryCount)
                            .isNull("finished_at")
                            .set("updated_at", LocalDateTime.now())
            );
            if (leaseRenewed == 0) {
                AiTask latestTask = aiTaskMapper.selectOne(
                        new LambdaQueryWrapper<AiTask>()
                                .eq(AiTask::getTaskId, task.getTaskId()));
                if (latestTask != null) {
                    copyTaskState(latestTask, task);
                }
                return isTaskInFlightOrSuccessful(task.getTaskStatus());
            }

            // 生成新的预签名 URL（有效期 1 小时）
            String presignedUrl = minioService.generatePresignedUrl(resumeFile.getObjectKey(), 1);

            // 构建 MQ 消息
            ResumeParseMessage message = ResumeParseMessage.builder()
                    .taskId(task.getTaskId())
                    .traceId(task.getTraceId())
                    .messageType("RESUME_PARSE_TASK")
                    .schemaVersion("1.0.0")
                    .retryCount(task.getRetryCount())
                    .createdAt(LocalDateTime.now())
                    .fileUrl(presignedUrl)
                    .mimeType(resumeFile.getMimeType())
                    .resumeFileId(resumeFile.getId().toString())
                    .build();

            // 发送到 MQ（单次发送，不重试）；发送成功后不再覆盖消费者可能已写入的最新状态。
            resumeTaskProducer.sendResumeParseTask(message);

            /*
             * 投递成功后保留 RETRYING，updated_at 即本次投递租约的起点。
             * 如果立即改回 PENDING，租约过期后无法区分“已经投递但未消费”和“从未投递”，
             * 会在正常消费延迟时重复发送同一 taskId。
             */
            log.info("任务重试投递成功，taskId={}, retryCount={}", task.getTaskId(), nextRetryCount);
            return true;

        } catch (Exception e) {
            log.error("任务重试失败，taskId={}", task.getTaskId(), e);

            AiTask latestTask = aiTaskMapper.selectOne(
                    new LambdaQueryWrapper<AiTask>()
                            .eq(AiTask::getTaskId, task.getTaskId()));
            if (latestTask != null) {
                copyTaskState(latestTask, task);
                if (isTaskInFlightOrSuccessful(latestTask.getTaskStatus())) {
                    // 发送调用可能在异常返回前已经成功到达 MQ，不能把成功结果降级。
                    return true;
                }
            }

            /*
             * 发送失败时保留 RETRYING，只有达到上限才落为最终 FAILED。
             * 这样发送前崩溃、预签名 URL 生成失败等异常都能在下一轮继续恢复。
             */
            int maxRetry = task.getMaxRetry() == null
                    ? resumeProperties.getMq().getMaxScheduledRetryCount()
                    : task.getMaxRetry();
            /*
             * 只有成功抢占过的实例才拥有回写租约。若异常发生在抢占前，
             * 不应凭观察到的旧状态修改数据库，避免误伤其他实例的任务。
             */
            if (claimedRetryCount < 0) {
                return false;
            }
            int retryCount = claimedRetryCount;
            boolean retryable = retryCount < maxRetry;
            task.setMaxRetry(maxRetry);
            task.setTaskStatus(retryable
                    ? TaskStatus.RETRYING.getCode()
                    : TaskStatus.FAILED.getCode());
            task.setErrorMessage(retryable
                    ? "定时任务重试失败：" + e.getMessage()
                    : String.format("已达最大重试次数 %d，定时任务重试失败：%s",
                    maxRetry, e.getMessage()));
            task.setFinishedAt(retryable ? null : LocalDateTime.now());
            int taskUpdated = aiTaskMapper.update(
                    null,
                    new UpdateWrapper<AiTask>()
                            .eq("task_id", task.getTaskId())
                            .eq("task_status", TaskStatus.RETRYING.getCode())
                            .eq("retry_count", claimedRetryCount)
                            .isNull("error_message")
                            .set("max_retry", maxRetry)
                            .set("task_status", task.getTaskStatus())
                            .set("error_message", task.getErrorMessage())
                            .set("finished_at", task.getFinishedAt())
                            .set("updated_at", LocalDateTime.now())
            );
            if (taskUpdated == 0) {
                AiTask latestTaskAfterFailure = aiTaskMapper.selectOne(
                        new LambdaQueryWrapper<AiTask>()
                                .eq(AiTask::getTaskId, task.getTaskId()));
                if (latestTaskAfterFailure != null) {
                    copyTaskState(latestTaskAfterFailure, task);
                }
                if (latestTaskAfterFailure != null
                        && isTaskInFlightOrSuccessful(latestTaskAfterFailure.getTaskStatus())) {
                    return true;
                }
                // 租约已被其他实例或结果消费者接管，当前实例不能继续回写关联文件。
                return false;
            }
            if (resumeFile != null) {
                resumeFile.setParseStatus(retryable
                        ? ParseStatus.PENDING.getCode()
                        : ParseStatus.FAILED.getCode());
                resumeFile.setErrorMessage(retryable
                        ? null
                        : String.format("已达最大重试次数 %d，定时任务重试失败：%s",
                        maxRetry, e.getMessage()));
                /*
                 * 异常处理可能晚于消费者完成消息，文件状态同样必须排除 SUCCESS/PROCESSING，
                 * 防止发送异常路径把已完成的解析结果降级。
                 */
                UpdateWrapper<ResumeFile> fileUpdate = new UpdateWrapper<ResumeFile>()
                        .eq("id", resumeFile.getId())
                        .set("parse_status", resumeFile.getParseStatus())
                        .set("error_message", resumeFile.getErrorMessage())
                        .set("updated_at", LocalDateTime.now());
                addCurrentLeaseCondition(fileUpdate, task.getTaskId(), claimedRetryCount);
                resumeFileMapper.update(null, fileUpdate);
            }

            return false;
        }
    }

    /**
     * 标记任务为永久失败
     * 达到最大重试次数后，不再重试
     *
     * @param task 失败的任务
     */
    private void markAsPermanentlyFailed(AiTask task) {
        AiTask latestTask = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, task.getTaskId()));
        if (latestTask == null) {
            return;
        }
        copyTaskState(latestTask, task);
        /*
         * 只有仍处于 FAILED 且尚未写入结束时间的任务才能由本方法收口。
         * PROCESSING/RETRYING 代表其他执行者仍持有任务，不能被旧调度快照改成 FAILED。
         */
        if (!TaskStatus.FAILED.getCode().equals(latestTask.getTaskStatus())
                || latestTask.getFinishedAt() != null) {
            return;
        }

        int maxRetry = task.getMaxRetry() == null
                ? resumeProperties.getMq().getMaxScheduledRetryCount()
                : task.getMaxRetry();
        // 数据库和枚举只定义 FAILED，使用统一状态避免写入未建模的字符串。
        task.setMaxRetry(maxRetry);
        task.setTaskStatus(TaskStatus.FAILED.getCode());
        task.setErrorMessage(String.format("已达最大重试次数 %d，标记为永久失败", maxRetry));
        task.setFinishedAt(LocalDateTime.now());
        int taskUpdated = aiTaskMapper.update(
                null,
                new UpdateWrapper<AiTask>()
                        .eq("task_id", task.getTaskId())
                        .eq("task_status", TaskStatus.FAILED.getCode())
                        .isNull("finished_at")
                        .apply("(COALESCE(retry_count, 0) >= {0})", maxRetry)
                        .set("max_retry", maxRetry)
                        .set("task_status", TaskStatus.FAILED.getCode())
                        .set("error_message", task.getErrorMessage())
                        .set("finished_at", task.getFinishedAt())
                        .set("updated_at", LocalDateTime.now())
        );
        if (taskUpdated == 0) {
            log.info("任务最终失败收口被并发状态变更跳过，taskId={}", task.getTaskId());
            return;
        }

        log.warn("任务标记为永久失败，taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
    }

    /**
     * 判断任务是否仍有调度重试次数，兼容历史数据中的空 retry_count/max_retry。
     */
    private boolean hasRetriesRemaining(AiTask task) {
        int retryCount = task.getRetryCount() == null ? 0 : task.getRetryCount();
        int maxRetry = task.getMaxRetry() == null
                ? resumeProperties.getMq().getMaxScheduledRetryCount()
                : task.getMaxRetry();
        return retryCount < maxRetry;
    }

    /**
     * 为简历文件回写绑定当前任务租约。
     *
     * <p>仅校验文件 ID 不足以区分旧调度实例和新调度实例。旧实例失去租约后，
     * 新实例可能已经递增 retry_count 并接管任务；通过任务 ID、状态和 retry_count
     * 的联合条件，旧实例的迟到回写会被数据库原子拒绝。</p>
     */
    private void addCurrentLeaseCondition(
            UpdateWrapper<ResumeFile> wrapper,
            String taskId,
            int retryCount
    ) {
        wrapper.eq("parse_task_id", taskId)
                .apply(
                        "EXISTS (SELECT 1 FROM ai_task " +
                                "WHERE task_id = {0} " +
                                "AND task_status = {1} " +
                                "AND retry_count = {2} " +
                                "AND finished_at IS NULL " +
                                "AND deleted = 0)",
                        taskId,
                        TaskStatus.RETRYING.getCode(),
                        retryCount);
    }

    /**
     * 判断任务是否已经由消费者接管或完成。
     *
     * 调度器遇到这两种状态时只能停止当前补偿动作，不能继续写回 RETRYING/FAILED。
     */
    private boolean isTaskInFlightOrSuccessful(String taskStatus) {
        return TaskStatus.PROCESSING.getCode().equals(taskStatus)
                || TaskStatus.SUCCESS.getCode().equals(taskStatus);
    }

    /**
     * 计算 stale PENDING 的恢复截止时间。
     *
     * 调度间隔配置为空或小于 1 分钟时仍使用 1 分钟，避免刚创建的任务在同一轮被立即重复投递。
     */
    private LocalDateTime calculateStalePendingCutoff() {
        Integer intervalMinutes = resumeProperties.getMq().getScheduledRetryIntervalMinutes();
        long safeIntervalMinutes = intervalMinutes == null ? 5L : Math.max(1L, intervalMinutes);
        return LocalDateTime.now().minusMinutes(safeIntervalMinutes);
    }

    /**
     * 将数据库中的最新状态同步到调度器当前对象，避免继续使用查询时的旧快照。
     */
    private void copyTaskState(AiTask source, AiTask target) {
        target.setId(source.getId());
        target.setTaskStatus(source.getTaskStatus());
        target.setRetryCount(source.getRetryCount());
        target.setMaxRetry(source.getMaxRetry());
        target.setErrorMessage(source.getErrorMessage());
        target.setFinishedAt(source.getFinishedAt());
    }
}
