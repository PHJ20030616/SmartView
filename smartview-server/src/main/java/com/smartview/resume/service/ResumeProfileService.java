package com.smartview.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.ParseStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.exception.BusinessException;
import com.smartview.common.validation.SchemaValidator;
import com.smartview.resume.dto.ResumeProfileDto;
import com.smartview.resume.dto.UpdateResumeProfileRequest;
import com.smartview.resume.entity.ResumeFile;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeFileMapper;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeParseResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历画像服务
 *
 * 功能说明：
 * - 处理 FastAPI AI 服务返回的简历解析结果
 * - 成功时创建 ResumeProfile 并更新关联状态
 * - 失败时更新失败状态和错误信息
 * - 通过 AiTask 状态实现幂等性，防止重复消费
 *
 * 幂等性保证：
 * - 以 AiTask.taskId 为幂等键
 * - 处理前检查 AiTask.taskStatus：已为 SUCCESS 或 FAILED 则跳过
 * - 同一 taskId 的重复消息不会重复创建画像
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Slf4j
@Service
public class ResumeProfileService {

    private final ResumeProfileMapper resumeProfileMapper;
    private final ResumeFileMapper resumeFileMapper;
    private final AiTaskMapper aiTaskMapper;
    private final ObjectMapper objectMapper;
    private final SchemaValidator schemaValidator;

    public ResumeProfileService(
            ResumeProfileMapper resumeProfileMapper,
            ResumeFileMapper resumeFileMapper,
            AiTaskMapper aiTaskMapper,
            ObjectMapper objectMapper,
            SchemaValidator schemaValidator) {
        this.resumeProfileMapper = resumeProfileMapper;
        this.resumeFileMapper = resumeFileMapper;
        this.aiTaskMapper = aiTaskMapper;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
    }

    /**
     * 处理简历解析结果消息的入口方法
     * 根据 success 字段分发到成功或失败处理分支
     *
     * @param message 解析结果消息
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleResult(ResumeParseResultMessage message) {
        // 注入 traceId 到 MDC，确保日志可追踪
        TraceIdContext.setTraceId(message.getTraceId());

        try {
            // 校验消息必需字段
            validateMessage(message);

            log.info("开始处理简历解析结果，taskId={}, resumeFileId={}, success={}",
                    message.getTaskId(), message.getResumeFileId(), message.getSuccess());

            if (Boolean.TRUE.equals(message.getSuccess())) {
                processSuccessResult(message);
            } else {
                processFailureResult(message);
            }
        } finally {
            // 清理 MDC，避免线程池复用时的 traceId 污染
            TraceIdContext.clear();
        }
    }

    /**
     * 处理解析成功的消息
     * 创建 ResumeProfile 并更新 ResumeFile 和 AiTask 状态
     *
     * 校验顺序（防伪造消息）：
     * 1. 根据 taskId 查询 AiTask，做幂等检查
     * 2. 先校验 AiTask.bizType/bizId 与消息 resumeFileId 一致（防止伪造 resumeFileId 绕过交叉校验）
     * 3. 用校验通过的 bizId 查询 ResumeFile
     * 4. 校验 ResumeFile.parseTaskId 与 taskId 一致、userId 与 AiTask.userId 一致
     *
     * @param message 解析结果消息
     */
    private void processSuccessResult(ResumeParseResultMessage message) {
        Long resumeFileId = parseResumeFileId(message.getResumeFileId());

        // 步骤 1：幂等性检查——已处理过的任务直接跳过
        AiTask aiTask = getAiTaskByTaskId(message.getTaskId());
        if (isTaskAlreadyProcessed(aiTask)) {
            log.info("任务已处理过，跳过重复消息，taskId={}, currentStatus={}",
                    message.getTaskId(), aiTask.getTaskStatus());
            return;
        }

        // 步骤 2：先校验 AiTask 的业务关联——防止伪造 resumeFileId 绕过后续校验
        validateAiTaskBizRelation(aiTask, resumeFileId);

        // 步骤 3：用校验通过的 bizId 查询简历文件
        ResumeFile resumeFile = resumeFileMapper.selectById(aiTask.getBizId());
        if (resumeFile == null) {
            // AiTask 的 bizId 指向一个不存在的简历文件，属于数据异常
            log.error("AiTask.bizId 指向的简历文件不存在，taskId={}, bizId={}",
                    message.getTaskId(), aiTask.getBizId());
            markTaskFinalFailed(aiTask, "关联的简历文件不存在（bizId=" + aiTask.getBizId() + "）");
            return;
        }

        // 步骤 4：校验 ResumeFile 侧的关联和用户归属
        validateResumeFileRelation(resumeFile, aiTask);

        // 计算画像版本号：同一简历文件的画像数量 + 1
        int version = getNextProfileVersion(resumeFileId);

        // 构建并保存简历画像
        ResumeProfile profile = buildResumeProfile(message, resumeFile, version);
        resumeProfileMapper.insert(profile);
        log.info("简历画像创建成功，profileId={}, resumeFileId={}, version={}",
                profile.getId(), resumeFileId, version);

        // 更新简历文件状态为解析成功
        resumeFile.setParseStatus(ParseStatus.SUCCESS.getCode());
        resumeFile.setErrorMessage(null);
        resumeFileMapper.updateById(resumeFile);

        // 更新 AI 任务状态为成功
        aiTask.setTaskStatus(TaskStatus.SUCCESS.getCode());
        aiTask.setFinishedAt(LocalDateTime.now());
        // 保存完整画像 JSON 到任务结果，便于审计和重试
        try {
            aiTask.setResultPayloadJson(objectMapper.writeValueAsString(profile));
        } catch (JsonProcessingException e) {
            // 序列化失败不阻塞主流程，记录日志即可
            log.warn("画像序列化失败，taskId={}", message.getTaskId(), e);
        }
        aiTaskMapper.updateById(aiTask);

        log.info("简历解析结果处理完成，taskId={}, resumeFileId={}, profileId={}",
                message.getTaskId(), resumeFileId, profile.getId());
    }

    /**
     * 处理解析失败的消息
     * 更新 ResumeFile 和 AiTask 的失败状态及错误信息
     *
     * @param message 解析结果消息
     */
    private void processFailureResult(ResumeParseResultMessage message) {
        Long resumeFileId = parseResumeFileId(message.getResumeFileId());

        // 步骤 1：幂等性检查
        AiTask aiTask = getAiTaskByTaskId(message.getTaskId());
        if (isTaskAlreadyProcessed(aiTask)) {
            log.info("任务已处理过，跳过重复消息，taskId={}, currentStatus={}",
                    message.getTaskId(), aiTask.getTaskStatus());
            return;
        }

        // 步骤 2：先校验 AiTask 的业务关联——防止伪造 resumeFileId
        validateAiTaskBizRelation(aiTask, resumeFileId);

        String errorMessage = message.getErrorMessage() != null
                ? message.getErrorMessage()
                : "AI 解析失败，未返回具体错误信息";

        // 步骤 3：用校验通过的 bizId 查询并更新简历文件
        ResumeFile resumeFile = resumeFileMapper.selectById(aiTask.getBizId());
        if (resumeFile != null) {
            try {
                validateResumeFileRelation(resumeFile, aiTask);
            } catch (BusinessException e) {
                // 交叉校验失败属于数据不匹配，直接抛出
                log.error("失败结果消息与数据库数据不匹配，拒绝更新，taskId={}, bizId={}, error={}",
                        message.getTaskId(), aiTask.getBizId(), e.getMessage());
                throw e;
            }
            boolean retryable = hasScheduledRetriesRemaining(aiTask);
            // 可重试失败不能把文件置为 FAILED，否则前端会提前结束轮询，调度器也无法表达“等待重试”。
            resumeFile.setParseStatus(retryable
                    ? ParseStatus.PENDING.getCode()
                    : ParseStatus.FAILED.getCode());
            resumeFile.setErrorMessage(retryable ? null : errorMessage);
            resumeFileMapper.updateById(resumeFile);
        } else {
            // AiTask.bizId 指向的文件不存在——数据异常
            log.warn("AiTask.bizId 指向的简历文件不存在，仅更新 AiTask，taskId={}, bizId={}",
                    message.getTaskId(), aiTask.getBizId());
        }

        // 更新 AI 任务状态为失败
        updateTaskFailed(aiTask, errorMessage);

        log.info("简历解析失败结果处理完成，taskId={}, resumeFileId={}, error={}",
                message.getTaskId(), resumeFileId, errorMessage);
    }

    /**
     * 根据消息字段构建 ResumeProfile 实体
     * 将消息中的 JSON 对象序列化为数据库 JSON 字段
     *
     * @param message     解析结果消息
     * @param resumeFile  关联的简历文件
     * @param version     画像版本号
     * @return 构建好的 ResumeProfile 实体
     */
    private ResumeProfile buildResumeProfile(
            ResumeParseResultMessage message, ResumeFile resumeFile, int version) {
        // 序列化各结构化字段为 JSON 字符串
        String contactInfoJson = toJsonSafely(message.getContactInfo());
        String educationJson = toJsonSafely(message.getEducation());
        String workExperienceJson = toJsonSafely(message.getWorkExperience());
        String projectExperienceJson = toJsonSafely(message.getProjectExperience());
        String skillsJson = toJsonSafely(message.getSkills());
        String profileJson = buildProfileJson(message);

        return ResumeProfile.builder()
                .userId(resumeFile.getUserId())
                .resumeFileId(resumeFile.getId())
                .candidateName(message.getCandidateName())
                .contactInfoJson(contactInfoJson)
                .educationJson(educationJson)
                .workExperienceJson(workExperienceJson)
                .projectExperienceJson(projectExperienceJson)
                .skillsJson(skillsJson)
                .rawText(message.getRawText())
                .profileJson(profileJson)
                .confirmStatus(ConfirmStatus.UNCONFIRMED.getCode())
                .version(version)
                .build();
    }

    /**
     * 构建完整的画像 JSON
     * 汇总所有结构化字段，便于前端一次性获取完整数据
     */
    private String buildProfileJson(ResumeParseResultMessage message) {
        Map<String, Object> profile = new HashMap<>();
        profile.put("candidateName", message.getCandidateName());
        profile.put("contactInfo", message.getContactInfo());
        profile.put("education", message.getEducation());
        profile.put("workExperience", message.getWorkExperience());
        profile.put("projectExperience", message.getProjectExperience());
        profile.put("skills", message.getSkills());
        return toJsonSafely(profile);
    }

    /**
     * 安全地将对象序列化为 JSON 字符串
     * 序列化失败时返回 null，不阻塞主流程
     */
    private String toJsonSafely(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败，返回 null", e);
            return null;
        }
    }

    /**
     * 计算下一个画像版本号
     * 查询同一简历文件下已有的画像数量，在此基础上 +1
     *
     * @param resumeFileId 简历文件 ID
     * @return 新画像的版本号
     */
    private int getNextProfileVersion(Long resumeFileId) {
        Long count = resumeProfileMapper.selectCount(
                new LambdaQueryWrapper<ResumeProfile>()
                        .eq(ResumeProfile::getResumeFileId, resumeFileId));
        return count.intValue() + 1;
    }

    /**
     * 根据 taskId 查询 AiTask
     * taskId 是业务唯一标识，跨服务保持一致
     */
    private AiTask getAiTaskByTaskId(String taskId) {
        AiTask aiTask = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, taskId)
                        /*
                         * 同一个 task_id 的成功和失败结果只能串行处理。
                         * 结果处理事务内锁住任务行，避免两个消费者同时通过幂等检查并重复创建画像。
                         */
                        .last("FOR UPDATE"));
        if (aiTask == null) {
            throw new BusinessException("AI 任务不存在，taskId=" + taskId);
        }
        return aiTask;
    }

    /**
     * 判断任务是否已处理完成（幂等性检查）
     * 已为 SUCCESS 或 FAILED 状态的任务无需再次处理
     */
    private boolean isTaskAlreadyProcessed(AiTask aiTask) {
        String status = aiTask.getTaskStatus();
        if (TaskStatus.SUCCESS.getCode().equals(status)) {
            return true;
        }
        // 兼容旧数据：FAILED 且仍有剩余重试次数时，不应吞掉迟到的成功结果。
        return TaskStatus.FAILED.getCode().equals(status)
                && !hasScheduledRetriesRemaining(aiTask);
    }

    /**
     * 根据剩余重试次数更新任务状态。
     * RETRYING 表示结果失败但调度器仍应继续投递；FAILED 才表示最终失败。
     */
    private void updateTaskFailed(AiTask aiTask, String errorMessage) {
        boolean retryable = hasScheduledRetriesRemaining(aiTask);
        aiTask.setTaskStatus(retryable
                ? TaskStatus.RETRYING.getCode()
                : TaskStatus.FAILED.getCode());
        aiTask.setErrorMessage(errorMessage);
        aiTask.setFinishedAt(retryable ? null : LocalDateTime.now());
        aiTaskMapper.updateById(aiTask);
    }

    /**
     * 关联数据缺失属于不可恢复的数据异常，不能进入普通重试流程。
     */
    private void markTaskFinalFailed(AiTask aiTask, String errorMessage) {
        aiTask.setTaskStatus(TaskStatus.FAILED.getCode());
        aiTask.setErrorMessage(errorMessage);
        aiTask.setFinishedAt(LocalDateTime.now());
        aiTaskMapper.updateById(aiTask);
    }

    /**
     * 判断任务是否仍可由定时调度器重试。
     * maxRetry 为空时按最终失败处理，避免历史脏数据被无限重试。
     */
    private boolean hasScheduledRetriesRemaining(AiTask aiTask) {
        Integer retryCount = aiTask.getRetryCount();
        Integer maxRetry = aiTask.getMaxRetry();
        return maxRetry != null
                && (retryCount == null ? 0 : retryCount) < maxRetry;
    }

    /**
     * 业务异常消息进入死信队列后，补偿更新任务和文件状态。
     * 原消息事务已回滚，因此必须使用新事务，确保前端不会永久停留在处理中。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markResultHandlingFailed(ResumeParseResultMessage message, String errorMessage) {
        if (message == null || message.getTaskId() == null || message.getTaskId().isBlank()) {
            log.warn("无法补偿无 taskId 的解析结果消息");
            return;
        }

        AiTask aiTask = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, message.getTaskId())
                        /*
                         * 死信补偿也必须与正常结果处理使用同一把任务行锁，
                         * 否则补偿可能在成功消费者提交前读取旧状态并覆盖成功结果。
                         */
                        .last("FOR UPDATE"));
        if (aiTask == null) {
            log.warn("死信补偿时未找到 AI 任务，taskId={}", message.getTaskId());
            return;
        }
        if (TaskStatus.SUCCESS.getCode().equals(aiTask.getTaskStatus())) {
            // 结果可能已被并发消费者成功处理，补偿不能把成功状态降级。
            log.info("死信补偿跳过已成功任务，taskId={}", message.getTaskId());
            return;
        }

        String reason = errorMessage == null || errorMessage.isBlank()
                ? "解析结果消息业务校验失败，消息已进入死信队列"
                : errorMessage;
        aiTask.setTaskStatus(TaskStatus.FAILED.getCode());
        aiTask.setErrorMessage(reason);
        aiTask.setFinishedAt(LocalDateTime.now());
        /*
         * 先用 task_id + 非 SUCCESS 条件原子更新任务。查询和写回之间可能有合法消费者
         * 并发完成任务，条件更新可以避免死信补偿把 SUCCESS 降级为 FAILED。
         */
        int taskUpdated = aiTaskMapper.update(
                null,
                new UpdateWrapper<AiTask>()
                        .eq("task_id", aiTask.getTaskId())
                        .ne("task_status", TaskStatus.SUCCESS.getCode())
                        .set("task_status", TaskStatus.FAILED.getCode())
                        .set("error_message", reason)
                        .set("finished_at", aiTask.getFinishedAt())
                        .set("updated_at", LocalDateTime.now())
        );
        if (taskUpdated == 0) {
            log.info("死信补偿未更新任务，任务可能已被并发处理为 SUCCESS，taskId={}",
                    aiTask.getTaskId());
            return;
        }

        if (aiTask.getBizId() == null) {
            log.warn("死信补偿任务缺少 bizId，taskId={}", message.getTaskId());
            return;
        }
        ResumeFile resumeFile = resumeFileMapper.selectById(aiTask.getBizId());
        if (resumeFile == null) {
            log.warn("死信补偿时未找到关联简历文件，taskId={}, bizId={}",
                    message.getTaskId(), aiTask.getBizId());
            return;
        }
        resumeFile.setParseStatus(ParseStatus.FAILED.getCode());
        resumeFile.setErrorMessage(reason);
        /*
         * 文件状态也必须独立做条件更新，覆盖“任务更新成功后、文件更新前消费者完成解析”的竞态窗口。
         */
        resumeFileMapper.update(
                null,
                new UpdateWrapper<ResumeFile>()
                        .eq("id", resumeFile.getId())
                        .ne("parse_status", ParseStatus.SUCCESS.getCode())
                        .set("parse_status", ParseStatus.FAILED.getCode())
                        .set("error_message", reason)
                        .set("updated_at", LocalDateTime.now())
        );
    }

    /**
     * 校验 AiTask 的业务关联（步骤 1——先于 ResumeFile 查询）
     * 在校验 AiTask.bizId 之前不能查询 ResumeFile，否则伪造的 resumeFileId
     * 可能导致"简历不存在"分支被触发，从而绕过交叉校验污染真实任务
     *
     * @param aiTask        AI 任务实体
     * @param resumeFileId  消息中声称的简历文件 ID
     * @throws BusinessException 关联关系不匹配时抛出
     */
    private void validateAiTaskBizRelation(AiTask aiTask, Long resumeFileId) {
        // 校验 1：AiTask 的 bizType 必须是 RESUME_FILE
        if (!BizType.RESUME_FILE.getCode().equals(aiTask.getBizType())) {
            throw new BusinessException(
                    "AiTask 业务类型不匹配，期望=RESUME_FILE，实际=" + aiTask.getBizType());
        }

        // 校验 2：AiTask.bizId 必须等于消息中的 resumeFileId
        // 此校验必须在查询 ResumeFile 之前执行，防止消息伪造 resumeFileId 绕过校验
        if (aiTask.getBizId() == null || !aiTask.getBizId().equals(resumeFileId)) {
            throw new BusinessException(
                    "消息 taskId 与 resumeFileId 不匹配：AiTask.bizId="
                            + aiTask.getBizId() + ", 消息 resumeFileId=" + resumeFileId);
        }
    }

    /**
     * 校验 ResumeFile 侧关联和用户归属（步骤 2——ResumeFile 确认存在后）
     *
     * @param resumeFile 简历文件实体
     * @param aiTask     AI 任务实体
     * @throws BusinessException 关联关系不匹配时抛出
     */
    private void validateResumeFileRelation(ResumeFile resumeFile, AiTask aiTask) {
        // 校验 1：ResumeFile.parseTaskId 必须等于 taskId
        String parseTaskId = resumeFile.getParseTaskId();
        if (parseTaskId == null || !parseTaskId.equals(aiTask.getTaskId())) {
            throw new BusinessException(
                    "ResumeFile.parseTaskId 与 taskId 不匹配：parseTaskId="
                            + parseTaskId + ", taskId=" + aiTask.getTaskId());
        }

        // 校验 2：ResumeFile.userId 必须等于 AiTask.userId
        // 防止跨用户的消息将数据写入他人的简历
        if (resumeFile.getUserId() == null || !resumeFile.getUserId().equals(aiTask.getUserId())) {
            throw new BusinessException(
                    "ResumeFile 与 AiTask 的 userId 不一致：ResumeFile.userId="
                            + resumeFile.getUserId() + ", AiTask.userId=" + aiTask.getUserId());
        }
    }

    /**
     * 将 resumeFileId 字符串转为 Long
     */
    private Long parseResumeFileId(String resumeFileId) {
        try {
            return Long.parseLong(resumeFileId);
        } catch (NumberFormatException e) {
            throw new BusinessException("resumeFileId 格式非法：" + resumeFileId);
        }
    }

    /**
     * 校验消息必需字段
     * 1. JSON Schema 校验：验证消息格式是否符合契约定义（messageType、retryCount 等）
     * 2. 业务规则校验：成功消息必须包含 rawText，失败消息必须包含 errorMessage
     * 3. 基础字段校验：验证 taskId、resumeFileId 等必填字段（Schema 加载失败时的兜底）
     */
    private void validateMessage(ResumeParseResultMessage message) {
        // 步骤 1：JSON Schema 校验（契约层面）
        try {
            schemaValidator.validateResumeParseResult(message);
        } catch (IllegalArgumentException e) {
            // Schema 校验失败，说明消息格式不符合契约，属于不可恢复的业务异常
            throw new BusinessException("消息格式校验失败：" + e.getMessage());
        }

        // 步骤 2：基础字段校验（Schema 未加载时的兜底）
        if (message.getTaskId() == null || message.getTaskId().isBlank()) {
            throw new BusinessException("消息缺少 taskId");
        }
        if (message.getResumeFileId() == null || message.getResumeFileId().isBlank()) {
            throw new BusinessException("消息缺少 resumeFileId");
        }
        if (message.getSuccess() == null) {
            throw new BusinessException("消息缺少 success 字段");
        }

        // 步骤 3：业务规则校验（Schema 无法表达的字段级约束）
        if (Boolean.TRUE.equals(message.getSuccess())) {
            if (message.getRawText() == null || message.getRawText().isBlank()) {
                throw new BusinessException("解析成功消息缺少 rawText，taskId=" + message.getTaskId());
            }
        } else {
            if (message.getErrorMessage() == null || message.getErrorMessage().isBlank()) {
                throw new BusinessException("解析失败消息缺少 errorMessage，taskId=" + message.getTaskId());
            }
        }
    }

    // ==================== 画像查询与确认 ====================

    /**
     * 查询简历画像（含所有权校验）
     * 将实体中的 JSON 字符串反序列化为结构化对象返回
     *
     * @param profileId 画像 ID
     * @param userId    当前登录用户 ID
     * @return 简历画像 DTO
     * @throws BusinessException 画像不存在或无权访问时抛出
     */
    @Transactional(readOnly = true)
    public ResumeProfileDto getProfileDto(Long profileId, Long userId) {
        ResumeProfile profile = resumeProfileMapper.selectById(profileId);
        if (profile == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "简历画像不存在");
        }
        if (!profile.getUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该简历画像");
        }
        return convertToDto(profile);
    }

    /**
     * 更新简历画像的关键字段（仅允许编辑姓名、联系方式和技能）
     * 已确认的画像不允许再编辑
     *
     * @param profileId 画像 ID
     * @param userId    当前登录用户 ID
     * @param request   更新请求（仅包含允许编辑的字段）
     * @return 更新后的简历画像 DTO
     * @throws BusinessException 画像不存在、无权访问或已确认时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeProfileDto updateProfile(Long profileId, Long userId, UpdateResumeProfileRequest request) {
        // 编辑与确认必须串行化，避免旧编辑快照把已确认状态覆盖回未确认。
        ResumeProfile profile = getProfileForUpdate(profileId);
        if (profile == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "简历画像不存在");
        }
        if (!profile.getUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该简历画像");
        }
        // 已确认的画像不允许再编辑，避免覆盖用户已确认的数据
        if (ConfirmStatus.CONFIRMED.getCode().equals(profile.getConfirmStatus())) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "已确认的简历画像不允许编辑");
        }

        // 仅更新非 null 的字段，null 表示不修改
        if (request.getCandidateName() != null) {
            profile.setCandidateName(request.getCandidateName());
        }
        if (request.getContactInfo() != null) {
            profile.setContactInfoJson(mergeContactInfo(
                    profile.getContactInfoJson(), request.getContactInfo()));
        }
        if (request.getSkills() != null) {
            profile.setSkillsJson(toJsonSafely(request.getSkills()));
        }

        resumeProfileMapper.updateById(profile);
        log.info("简历画像更新成功，profileId={}, userId={}", profileId, userId);
        return convertToDto(profile);
    }

    /**
     * 合并联系方式更新，避免轻量编辑覆盖 AI 解析出的未展示字段。
     * 请求中的 null 表示删除对应字段；未出现在请求中的字段保持原值。
     */
    private String mergeContactInfo(
            String existingContactInfoJson, Map<String, Object> updates) {
        Map<String, Object> merged = parseJsonToMap(existingContactInfoJson);
        if (merged == null) {
            merged = new HashMap<>();
        }

        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            if (entry.getValue() == null) {
                merged.remove(entry.getKey());
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return toJsonSafely(merged);
    }

    /**
     * 确认简历画像
     * 将确认状态从 UNCONFIRMED 更新为 CONFIRMED，并记录确认时间
     * 已确认的画像重复调用不会报错（幂等）
     *
     * @param profileId 画像 ID
     * @param userId    当前登录用户 ID
     * @return 确认后的简历画像 DTO
     * @throws BusinessException 画像不存在或无权访问时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ResumeProfileDto confirmProfile(Long profileId, Long userId) {
        // 与编辑操作共用画像行锁，确保确认状态不会被并发旧快照回写覆盖。
        ResumeProfile profile = getProfileForUpdate(profileId);
        if (profile == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "简历画像不存在");
        }
        if (!profile.getUserId().equals(userId)) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该简历画像");
        }
        // 已确认的画像不报错，幂等返回
        if (ConfirmStatus.CONFIRMED.getCode().equals(profile.getConfirmStatus())) {
            log.info("简历画像已确认，无需重复操作，profileId={}", profileId);
            return convertToDto(profile);
        }

        profile.setConfirmStatus(ConfirmStatus.CONFIRMED.getCode());
        profile.setConfirmedAt(LocalDateTime.now());
        resumeProfileMapper.updateById(profile);
        log.info("简历画像确认成功，profileId={}, userId={}", profileId, userId);
        return convertToDto(profile);
    }

    /**
     * 查询并锁定画像行，供会修改画像状态或内容的事务使用。
     *
     * 画像确认是不可逆业务状态；如果编辑和确认并发读取同一份旧实体，
     * 后提交的整行更新可能覆盖先提交的 CONFIRMED 状态，因此写操作必须在数据库层串行化。
     */
    private ResumeProfile getProfileForUpdate(Long profileId) {
        return resumeProfileMapper.selectOne(
                new LambdaQueryWrapper<ResumeProfile>()
                        .eq(ResumeProfile::getId, profileId)
                        .last("FOR UPDATE"));
    }

    /**
     * 将 ResumeProfile 实体转换为 DTO
     * 将数据库中的 JSON 字符串字段反序列化为 Java 对象
     *
     * @param entity 简历画像实体
     * @return 简历画像 DTO
     */
    private ResumeProfileDto convertToDto(ResumeProfile entity) {
        return ResumeProfileDto.builder()
                .id(entity.getId().toString())
                .userId(entity.getUserId().toString())
                .resumeFileId(entity.getResumeFileId().toString())
                .candidateName(entity.getCandidateName())
                .contactInfo(parseJsonToMap(entity.getContactInfoJson()))
                .education(parseJsonToList(entity.getEducationJson()))
                .workExperience(parseJsonToList(entity.getWorkExperienceJson()))
                .projectExperience(parseJsonToList(entity.getProjectExperienceJson()))
                .skills(parseJsonToSkillsList(entity.getSkillsJson()))
                .rawText(entity.getRawText())
                .confirmStatus(entity.getConfirmStatus())
                .confirmedAt(entity.getConfirmedAt())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 将 JSON 字符串反序列化为 Map<String, Object>
     */
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("JSON 反序列化失败（Map），返回 null", e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为 List<Map<String, Object>>
     */
    private List<Map<String, Object>> parseJsonToList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            log.warn("JSON 反序列化失败（List），返回 null", e);
            return null;
        }
    }

    /**
     * 将 JSON 字符串反序列化为 List<String>（技能列表）
     * 兼容技能存储为对象 Map 的场景（如 {"languages": ["Java"], "frameworks": ["Spring"]}），
     * 此时提取所有值并扁平化为单一列表
     */
    @SuppressWarnings("unchecked")
    private List<String> parseJsonToSkillsList(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            // 先尝试按字符串列表反序列化
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            // 兼容技能以 Map 形式存储的情况（{"languages": [...], "frameworks": [...]}）
            try {
                Map<String, Object> skillsMap = objectMapper.readValue(
                        json, new TypeReference<Map<String, Object>>() {});
                List<String> flatList = new java.util.ArrayList<>();
                for (Object value : skillsMap.values()) {
                    if (value instanceof List) {
                        for (Object item : (List<?>) value) {
                            if (item instanceof String) {
                                flatList.add((String) item);
                            }
                        }
                    } else if (value instanceof String) {
                        flatList.add((String) value);
                    }
                }
                return flatList;
            } catch (JsonProcessingException ex) {
                log.warn("技能 JSON 反序列化失败，返回 null", ex);
                return null;
            }
        }
    }

    /**
     * 根据简历文件 ID 查找最新版本的画像 ID
     * 用于前端在解析完成后跳转到确认页面时获取画像标识
     *
     * @param resumeFileId 简历文件 ID
     * @return 最新版本画像的 ID，不存在则返回 null
     */
    public Long findLatestProfileIdByFileId(Long resumeFileId) {
        ResumeProfile profile = resumeProfileMapper.selectOne(
                new LambdaQueryWrapper<ResumeProfile>()
                        .eq(ResumeProfile::getResumeFileId, resumeFileId)
                        .orderByDesc(ResumeProfile::getVersion)
                        .last("LIMIT 1"));
        return profile != null ? profile.getId() : null;
    }
}
