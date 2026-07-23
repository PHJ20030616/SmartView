package com.smartview.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.ParseStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.infra.minio.MinioService;
import com.smartview.resume.entity.ResumeFile;
import com.smartview.resume.mapper.ResumeFileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeParseMessage;
import com.smartview.task.mq.ResumeTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final AiTaskMapper aiTaskMapper;
    private final MinioService minioService;
    private final ResumeTaskProducer resumeTaskProducer;
    private final ResumeProperties resumeProperties;

    /**
     * 构造函数注入依赖
     */
    public ResumeFileService(
            ResumeFileMapper resumeFileMapper,
            AiTaskMapper aiTaskMapper,
            MinioService minioService,
            ResumeTaskProducer resumeTaskProducer,
            ResumeProperties resumeProperties
    ) {
        this.resumeFileMapper = resumeFileMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.minioService = minioService;
        this.resumeTaskProducer = resumeTaskProducer;
        this.resumeProperties = resumeProperties;
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

            // 6. MQ 投递（立即重试 3 次）
            boolean mqSuccess = sendToMqWithRetry(resumeFile, aiTask);

            if (!mqSuccess) {
                // MQ 投递失败，标记任务为 FAILED 但不回滚事务
                aiTask.setTaskStatus(TaskStatus.FAILED.getCode());
                aiTask.setErrorMessage("MQ 投递失败，已进入重试队列");
                aiTaskMapper.updateById(aiTask);
                log.warn("MQ 投递失败，resumeFileId={}, taskId={}, 等待定时任务重试",
                        resumeFile.getId(), aiTask.getTaskId());
            }

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
