package com.smartview.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.ParseStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.infra.minio.MinioService;
import com.smartview.resume.entity.ResumeFile;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeFileMapper;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeParseMessage;
import com.smartview.task.mq.ResumeTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 简历文件业务服务
 *
 * 功能说明：
 * - 处理简历文件上传、校验、存储、解析任务创建等核心业务逻辑
 * - 实现立即重试 + 前端轮询 + 定时任务兜底的三重保障机制
 * - 保证数据库和 MinIO 的最终一致性
 *
 * 上传流程：
 * 1. 文件校验（类型、大小）
 * 2. 计算文件哈希（SHA-256）
 * 3. 上传到 MinIO
 * 4. 数据库事务内：写入 resume_file 和 ai_task
 * 5. MQ 投递：立即重试 3 次（指数退避）
 * 6. 失败处理：标记 FAILED，等待定时任务重试
 * 7. 回滚：数据库失败时清理 MinIO 文件
 *
 * 异常处理策略：
 * - 文件校验失败：直接抛异常，不上传
 * - MinIO 上传失败：直接抛异常，无副作用
 * - 数据库失败：回滚事务，清理 MinIO 文件
 * - MQ 投递失败：不回滚，标记 FAILED，定时任务兜底
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Slf4j
@Service
public class ResumeFileService {

    private final ResumeFileMapper resumeFileMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final AiTaskMapper aiTaskMapper;
    private final MinioService minioService;
    private final ResumeTaskProducer resumeTaskProducer;
    private final ResumeProperties resumeProperties;
    private final ResumeVectorizationService resumeVectorizationService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 主构造函数（Spring 依赖注入入口）。
     * 类中存在多个构造方法时，Spring 无法自动选择注入目标，必须通过 @Autowired 显式指定主构造方法，
     * 否则应用启动时会报 “No default constructor found” 错误。
     */
    @Autowired
    public ResumeFileService(
            ResumeFileMapper resumeFileMapper,
            ResumeProfileMapper resumeProfileMapper,
            AiTaskMapper aiTaskMapper,
            MinioService minioService,
            ResumeTaskProducer resumeTaskProducer,
            ResumeProperties resumeProperties,
            ResumeVectorizationService resumeVectorizationService,
            TransactionTemplate transactionTemplate
    ) {
        this.resumeFileMapper = resumeFileMapper;
        this.resumeProfileMapper = resumeProfileMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.minioService = minioService;
        this.resumeTaskProducer = resumeTaskProducer;
        this.resumeProperties = resumeProperties;
        this.resumeVectorizationService = resumeVectorizationService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 保留旧测试和非 Spring 调用方的构造函数；生产环境使用包含向量服务的构造函数。
     */
    public ResumeFileService(
            ResumeFileMapper resumeFileMapper,
            AiTaskMapper aiTaskMapper,
            MinioService minioService,
            ResumeTaskProducer resumeTaskProducer,
            ResumeProperties resumeProperties,
            TransactionTemplate transactionTemplate
    ) {
        this(
                resumeFileMapper,
                null,
                aiTaskMapper,
                minioService,
                resumeTaskProducer,
                resumeProperties,
                null,
                transactionTemplate);
    }

    /**
     * 上传简历文件
     * 核心业务逻辑：校验 -> MinIO -> 数据库事务 -> MQ 投递（带重试）
     *
     * @param file   上传的文件
     * @param userId 用户 ID
     * @return 简历文件实体
     * @throws BusinessException 校验失败或上传失败时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeFile uploadResume(MultipartFile file, Long userId) {
        // 1. 文件校验
        validateFile(file);

        // 2. 计算文件哈希（用于去重和完整性校验）
        String fileHash = calculateFileHash(file);

        // 3. 上传到 MinIO
        String objectKey = minioService.uploadResumeFile(file, userId);

        try {
            // 4. 创建 ResumeFile 记录（事务内）
            ResumeFile resumeFile = createResumeFileRecord(file, userId, objectKey, fileHash);

            // 5. 创建 AiTask 记录（事务内）
            AiTask aiTask = createAiTaskRecord(resumeFile);

            // 6. 事务提交后投递 MQ 消息，防止 AI 在事务提交前返回结果导致消费者查不到任务
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                boolean mqSuccess = sendToMqWithRetry(resumeFile, aiTask);

                                if (mqSuccess) {
                                    /*
                                     * 初次投递成功后将任务切换为 RETRYING，使用 updated_at 作为投递租约。
                                     * 不能继续保留 PENDING，否则消息消费延迟超过调度间隔时会被重复投递。
                                     * 若消费者已经抢先写入 PROCESSING/SUCCESS，条件更新会自然放弃回写。
                                     */
                                    transactionTemplate.executeWithoutResult(status ->
                                            markParseSent(aiTask));
                                } else {
                                    // MQ 投递失败，在新事务中同时标记任务和文件为 FAILED，
                                    // 否则前端轮询会一直看到 PENDING，直到超时且无法展示真实原因。
                                    // afterCommit 回调中外部事务已完成，需独立事务确保状态持久化
                                    transactionTemplate.executeWithoutResult(status ->
                                            markParseFailed(
                                                    resumeFile, aiTask, "MQ 投递失败，已进入重试队列"));
                                    log.warn("MQ 投递失败，resumeFileId={}, taskId={}, 等待定时任务重试",
                                            resumeFile.getId(), aiTask.getTaskId());
                                }
                            } catch (Exception e) {
                                // afterCommit 中的异常无法被外层 try-catch 捕获，
                                // 必须在此处兜底处理，防止事务已提交后异常逃逸导致状态不一致
                                log.error("MQ 投递发生未预期异常，resumeFileId={}, taskId={}",
                                        resumeFile.getId(), aiTask.getTaskId(), e);
                                // 尝试在新事务中标记任务失败
                                try {
                                    transactionTemplate.executeWithoutResult(status ->
                                            markParseFailed(
                                                    resumeFile, aiTask, "MQ 投递异常：" + e.getMessage()));
                                } catch (Exception innerEx) {
                                    log.error("标记任务失败也失败，resumeFileId={}, taskId={}",
                                            resumeFile.getId(), aiTask.getTaskId(), innerEx);
                                }
                            }
                        }
                    });

            return resumeFile;

        } catch (Exception e) {
            // 数据库失败，回滚事务并清理 MinIO 文件
            log.error("数据库操作失败，清理 MinIO 文件，objectKey={}, userId={}", objectKey, userId, e);
            minioService.deleteFile(objectKey);
            throw new BusinessException("简历上传失败：" + e.getMessage());
        }
    }

    /**
     * 根据 ID 查询简历文件
     *
     * @param resumeFileId 简历文件 ID
     * @return 简历文件实体
     * @throws BusinessException 文件不存在时抛出
     */
    public ResumeFile getResumeFile(Long resumeFileId) {
        ResumeFile resumeFile = resumeFileMapper.selectById(resumeFileId);
        if (resumeFile == null) {
            throw new BusinessException("简历文件不存在");
        }
        return resumeFile;
    }

    /**
     * 查询用户的所有简历文件
     *
     * @param userId 用户 ID
     * @return 简历文件列表
     */
    public List<ResumeFile> getUserResumeFiles(Long userId) {
        return resumeFileMapper.selectList(
                new LambdaQueryWrapper<ResumeFile>()
                        .eq(ResumeFile::getUserId, userId)
                        .orderByDesc(ResumeFile::getUploadedAt)
        );
    }

    /**
     * 删除用户的简历文件及其全部画像版本。
     *
     * <p>MySQL 中的软删除和 DELETE 向量任务在同一事务内提交，确保删除操作发生后，
     * 已经确认的画像不会继续作为有效数据被读取。向量库与 MinIO 都属于外部依赖，
     * 它们的临时异常只记录日志并由后续任务补偿，不能回滚 MySQL 的权威删除状态。</p>
     *
     * @param resumeFileId 简历文件 ID
     * @param userId 当前登录用户 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long resumeFileId, Long userId) {
        ResumeFile resumeFile = resumeFileMapper.selectOne(
                new LambdaQueryWrapper<ResumeFile>()
                        .eq(ResumeFile::getId, resumeFileId)
                        .eq(ResumeFile::getUserId, userId)
                        .last("FOR UPDATE"));
        if (resumeFile == null) {
            // 不区分“文件不存在”和“文件属于他人”，避免泄露跨用户资源是否存在。
            throw new BusinessException("简历文件不存在");
        }

        List<ResumeProfile> profiles = resumeProfileMapper == null
                ? List.of()
                : resumeProfileMapper.selectList(
                        new LambdaQueryWrapper<ResumeProfile>()
                                .eq(ResumeProfile::getResumeFileId, resumeFileId)
                                .last("FOR UPDATE"));

        // 先写入删除任务，再软删除画像；DELETE worker 不依赖画像仍处于有效状态，
        // 但任务记录必须和本次删除一起提交，才能保证删除后有可追踪的清理动作。
        if (resumeVectorizationService != null) {
            for (ResumeProfile profile : profiles) {
                resumeVectorizationService.ensureDeleteTask(profile);
            }
        }

        for (ResumeProfile profile : profiles) {
            resumeProfileMapper.deleteById(profile.getId());
        }
        resumeFileMapper.deleteById(resumeFile.getId());
        scheduleMinioDeleteAfterCommit(resumeFile.getObjectKey(), resumeFile.getId());

        log.info("简历文件已标记删除，userId={}, resumeFileId={}, profileCount={}",
                userId, resumeFileId, profiles.size());
    }

    /**
     * 事务提交后清理对象存储文件。
     *
     * <p>MinIO 删除失败不能影响 MySQL 已提交的软删除结果；向量和对象存储均属于
     * 可重试的派生数据，后续清理任务可以根据 objectKey 继续补偿。</p>
     */
    private void scheduleMinioDeleteAfterCommit(String objectKey, Long resumeFileId) {
        if (objectKey == null || objectKey.isBlank()) {
            return;
        }
        Runnable delete = () -> {
            try {
                minioService.deleteFile(objectKey);
            } catch (Exception exception) {
                log.error("简历文件已删除但 MinIO 清理失败，等待后续补偿，resumeFileId={}, objectKey={}",
                        resumeFileId, objectKey, exception);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            // 兼容非事务测试或内部调用；正式删除入口始终由 @Transactional 执行。
            delete.run();
        }
    }

    /**
     * 校验文件类型和大小
     *
     * @param file 上传的文件
     * @throws BusinessException 校验失败时抛出
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }

        // 校验文件大小
        Long maxFileSize = resumeProperties.getMaxFileSize();
        if (file.getSize() > maxFileSize) {
            throw new BusinessException(String.format("文件大小不能超过 %d MB", maxFileSize / 1024 / 1024));
        }

        // 校验文件类型
        String contentType = file.getContentType();
        List<String> allowedTypes = Arrays.asList(resumeProperties.getAllowedMimeTypes().split(","));
        if (contentType == null || !allowedTypes.contains(contentType)) {
            throw new BusinessException("仅支持 PDF 格式的简历文件");
        }

        // 校验文件名
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".pdf")) {
            throw new BusinessException("文件名必须以 .pdf 结尾");
        }
    }

    /**
     * 计算文件 SHA-256 哈希值
     * 用于去重检测和完整性校验
     *
     * @param file 上传的文件
     * @return SHA-256 哈希值（十六进制字符串）
     */
    private String calculateFileHash(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(file.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.warn("文件哈希计算失败，跳过去重检测", e);
            return null;
        }
    }

    /**
     * 创建 ResumeFile 记录
     *
     * @param file       上传的文件
     * @param userId     用户 ID
     * @param objectKey  MinIO 对象 Key
     * @param fileHash   文件哈希
     * @return ResumeFile 实体
     */
    private ResumeFile createResumeFileRecord(MultipartFile file, Long userId, String objectKey, String fileHash) {
        ResumeFile resumeFile = ResumeFile.builder()
                .userId(userId)
                .uploadedAt(LocalDateTime.now())
                .originalFilename(file.getOriginalFilename())
                .objectKey(objectKey)
                .fileHash(fileHash)
                .fileSize(file.getSize())
                .mimeType(file.getContentType())
                .parseStatus(ParseStatus.PENDING.getCode())
                .build();

        resumeFileMapper.insert(resumeFile);
        log.info("ResumeFile 记录创建成功，id={}, userId={}, objectKey={}",
                resumeFile.getId(), userId, objectKey);
        return resumeFile;
    }

    /**
     * 创建 AiTask 记录
     *
     * @param resumeFile 简历文件实体
     * @return AiTask 实体
     */
    private AiTask createAiTaskRecord(ResumeFile resumeFile) {
        String taskId = UUID.randomUUID().toString();
        String traceId = TraceIdContext.currentTraceId();

        AiTask aiTask = AiTask.builder()
                .taskId(taskId)
                .userId(resumeFile.getUserId())
                .taskType(TaskType.RESUME_PARSE.getCode())
                .taskStatus(TaskStatus.PENDING.getCode())
                .bizType(BizType.RESUME_FILE.getCode())
                .bizId(resumeFile.getId())
                .retryCount(0)
                .maxRetry(resumeProperties.getMq().getMaxScheduledRetryCount())
                .traceId(traceId)
                .messageType("RESUME_PARSE_TASK")
                .schemaVersion("1.0.0")
                .build();

        aiTaskMapper.insert(aiTask);
        log.info("AiTask 记录创建成功，taskId={}, resumeFileId={}", taskId, resumeFile.getId());

        // 更新 resumeFile 的 parseTaskId
        resumeFile.setParseTaskId(taskId);
        resumeFileMapper.updateById(resumeFile);

        return aiTask;
    }

    /**
     * 记录初次 MQ 投递成功。
     *
     * <p>任务创建后先处于 PENDING，只有确认消息已交给 MQ 后才进入 RETRYING。
     * RETRYING 在这里表示“投递租约仍有效”，并不代表 AI 已经返回失败结果；
     * 这样调度器可以在租约过期后恢复投递，同时避免正常消费延迟导致重复投递。</p>
     */
    private void markParseSent(AiTask aiTask) {
        int updated = aiTaskMapper.update(
                null,
                new UpdateWrapper<AiTask>()
                        .eq("task_id", aiTask.getTaskId())
                        .eq("task_status", TaskStatus.PENDING.getCode())
                        .apply("(retry_count IS NULL OR retry_count = 0)")
                        .isNull("finished_at")
                        .set("task_status", TaskStatus.RETRYING.getCode())
                        .set("retry_count", 0)
                        .set("error_message", null)
                        .set("updated_at", LocalDateTime.now())
        );
        if (updated == 0) {
            log.info("初次 MQ 投递成功后的状态回写被并发状态变更跳过，taskId={}", aiTask.getTaskId());
        }
    }

    /**
     * 标记任务为失败状态
     * 独立方法，可在新事务中执行（afterCommit 需要独立事务）
     */
    private void markParseFailed(ResumeFile resumeFile, AiTask aiTask, String errorMessage) {
        AiTask currentTask = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, aiTask.getTaskId())
                        /*
                         * afterCommit 补偿与结果消费者共享任务行锁，确保读取状态后到回写状态之间
                         * 不会出现“先读到 PENDING、后把 SUCCESS 降级”的窗口。
                         */
                        .last("FOR UPDATE"));
        if (currentTask == null) {
            log.warn("MQ 投递失败后未找到任务，跳过状态补偿，taskId={}", aiTask.getTaskId());
            return;
        }

        int maxRetry = currentTask.getMaxRetry() == null
                ? resumeProperties.getMq().getMaxScheduledRetryCount()
                : currentTask.getMaxRetry();
        int retryCount = currentTask.getRetryCount() == null ? 0 : currentTask.getRetryCount();
        boolean retryable = retryCount < maxRetry;
        String nextTaskStatus = retryable
                ? TaskStatus.RETRYING.getCode()
                : TaskStatus.FAILED.getCode();

        /*
         * MQ 发送结果存在“消息已到达但生产者超时”的不确定窗口。
         * 使用带状态条件的更新，避免把结果消费者刚写入的 SUCCESS 降级为失败。
         */
        int taskUpdated = aiTaskMapper.update(
                null,
                new UpdateWrapper<AiTask>()
                        .eq("task_id", currentTask.getTaskId())
                        /*
                         * 该补偿只属于上传后的首次投递。若任务已经进入 RETRYING/FAILED，
                         * 说明消费者或调度器已经处理过，不能用“MQ 投递失败”覆盖真实错误。
                         */
                        .eq("task_status", TaskStatus.PENDING.getCode())
                        .apply("(retry_count IS NULL OR retry_count = 0)")
                        .isNull("finished_at")
                        .isNull("error_message")
                        .set("max_retry", maxRetry)
                        .set("task_status", nextTaskStatus)
                        .set("error_message", errorMessage)
                        .set("finished_at", retryable ? null : LocalDateTime.now())
                        .set("updated_at", LocalDateTime.now())
        );
        if (taskUpdated == 0) {
            log.info("MQ 投递失败补偿跳过已完成任务，taskId={}", currentTask.getTaskId());
            return;
        }

        Long resumeFileId = currentTask.getBizId() != null
                ? currentTask.getBizId()
                : resumeFile.getId();
        if (resumeFileId == null) {
            log.warn("MQ 投递失败补偿缺少简历文件 ID，taskId={}", currentTask.getTaskId());
            return;
        }

        /*
         * 可重试失败必须保持 PENDING，否则前端会立即停止轮询；
         * 只有没有剩余调度重试次数时才向用户展示最终 FAILED。
         * 同样使用条件更新，防止并发成功结果被覆盖。
         */
        resumeFileMapper.update(
                null,
                new UpdateWrapper<ResumeFile>()
                        .eq("id", resumeFileId)
                        .eq("parse_task_id", currentTask.getTaskId())
                        .eq("parse_status", ParseStatus.PENDING.getCode())
                        .set("parse_status",
                                retryable
                                        ? ParseStatus.PENDING.getCode()
                                        : ParseStatus.FAILED.getCode())
                        .set("error_message", retryable ? null : errorMessage)
                        .set("updated_at", LocalDateTime.now())
        );
    }

    /**
     * 发送 MQ 消息（带立即重试）
     * 使用配置的重试策略：默认 3 次，指数退避（100ms -> 300ms -> 900ms）
     *
     * @param resumeFile 简历文件实体
     * @param aiTask     AI 任务实体
     * @return true=成功，false=重试后仍失败
     */
    private boolean sendToMqWithRetry(ResumeFile resumeFile, AiTask aiTask) {
        // 生成预签名 URL（有效期 1 小时）
        String presignedUrl = minioService.generatePresignedUrl(resumeFile.getObjectKey(), 1);

        // 构建 MQ 消息
        ResumeParseMessage message = ResumeParseMessage.builder()
                .taskId(aiTask.getTaskId())
                .traceId(aiTask.getTraceId())
                .messageType("RESUME_PARSE_TASK")
                .schemaVersion("1.0.0")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .fileUrl(presignedUrl)
                .mimeType(resumeFile.getMimeType())
                .resumeFileId(resumeFile.getId().toString())
                .build();

        // 立即重试发送
        int maxAttempts = resumeProperties.getMq().getMaxRetryAttempts();
        long baseDelay = resumeProperties.getMq().getRetryBaseDelayMs();
        return resumeTaskProducer.sendResumeParseTaskWithRetry(message, maxAttempts, baseDelay);
    }
}
