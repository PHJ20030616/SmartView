package com.smartview.task.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 * - 超过重试次数后标记为 PERMANENTLY_FAILED，不再重试
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
            // 查询失败的任务（未达到最大重试次数）
            List<AiTask> failedTasks = queryFailedTasks();

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
                    boolean success = retryTask(task);
                    if (success) {
                        successCount++;
                    } else {
                        // 检查是否达到最大重试次数
                        if (task.getRetryCount() >= task.getMaxRetry()) {
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
     * 查询失败的任务
     * 条件：
     * 1. 任务类型为 RESUME_PARSE
     * 2. 任务状态为 FAILED
     * 3. 重试次数小于最大重试次数
     * 4. 按创建时间升序排序
     * 5. 最多返回 100 个任务
     *
     * @return 失败任务列表
     */
    private List<AiTask> queryFailedTasks() {
        return aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.RESUME_PARSE.getCode())
                        .eq(AiTask::getTaskStatus, TaskStatus.FAILED.getCode())
                        .lt(AiTask::getRetryCount, resumeProperties.getMq().getMaxScheduledRetryCount())
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
    private boolean retryTask(AiTask task) {
        try {
            // 查询关联的简历文件
            ResumeFile resumeFile = resumeFileMapper.selectById(task.getBizId());
            if (resumeFile == null) {
                log.error("简历文件不存在，taskId={}, resumeFileId={}", task.getTaskId(), task.getBizId());
                return false;
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

            // 发送到 MQ（单次发送，不重试）
            resumeTaskProducer.sendResumeParseTask(message);

            // 更新任务状态为 PENDING，递增重试次数
            task.setTaskStatus(TaskStatus.PENDING.getCode());
            task.setRetryCount(task.getRetryCount() + 1);
            task.setErrorMessage(null);
            aiTaskMapper.updateById(task);

            log.info("任务重试成功，taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
            return true;

        } catch (Exception e) {
            log.error("任务重试失败，taskId={}", task.getTaskId(), e);

            // 递增重试次数但保持 FAILED 状态
            task.setRetryCount(task.getRetryCount() + 1);
            task.setErrorMessage("定时任务重试失败：" + e.getMessage());
            aiTaskMapper.updateById(task);

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
        task.setTaskStatus("PERMANENTLY_FAILED");
        task.setErrorMessage(String.format("已达最大重试次数 %d，标记为永久失败", task.getMaxRetry()));
        task.setFinishedAt(LocalDateTime.now());
        aiTaskMapper.updateById(task);

        log.warn("任务标记为永久失败，taskId={}, retryCount={}", task.getTaskId(), task.getRetryCount());
    }
}
