package com.smartview.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.RoleDirection;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.common.validation.SchemaValidator;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.profile.entity.ProfileAnalysis;
import com.smartview.profile.mapper.ProfileAnalysisMapper;
import com.smartview.resume.dto.ProfileAnalysisStatusDto;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ProfileAnalyzeMessage;
import com.smartview.task.mq.ProfileAnalyzeResultMessage;
import com.smartview.task.mq.ProfileAnalyzeTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 画像分析任务服务。
 *
 * 关键一致性规则：
 * 1. 画像确认状态、向量完成校验、任务记录在同一 MySQL 事务中处理；
 * 2. 创建 PROFILE_ANALYZE 任务前必须校验简历向量已成功入库（requireVectorizeCompleted），
 *    避免在没有向量上下文的情况下盲目触发分析；
 * 3. MQ 只在事务提交后发送，MQ/Chroma 异常不能影响已确认的画像；
 * 4. 唯一约束 (resume_profile_id, role_direction, profile_version) 保证同一简历版本、
 *    同一面试方向只有一份有效画像分析；profile_analysis 仅在分析成功时写入，
 *    失败/重试由 ai_task 承载；
 * 5. 手工重试创建新 taskId，保留旧任务审计记录，避免迟到结果污染新任务。
 *
 * @author SmartView Team
 * @since 2026-08-03
 */
@Slf4j
@Service
public class ProfileAnalysisTaskService {

    private static final String MESSAGE_TYPE = "PROFILE_ANALYZE_TASK";
    private static final String RESULT_MESSAGE_TYPE = "PROFILE_ANALYZE_RESULT";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String OPERATION_UPSERT = "UPSERT";

    private final AiTaskMapper aiTaskMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final ProfileAnalysisMapper profileAnalysisMapper;
    private final ProfileAnalyzeTaskProducer producer;
    private final ResumeProperties resumeProperties;
    private final ObjectMapper objectMapper;
    private final SchemaValidator schemaValidator;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ProfileAnalysisTaskService(
            AiTaskMapper aiTaskMapper,
            ResumeProfileMapper resumeProfileMapper,
            ProfileAnalysisMapper profileAnalysisMapper,
            ProfileAnalyzeTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            SchemaValidator schemaValidator,
            PlatformTransactionManager transactionManager) {
        this.aiTaskMapper = aiTaskMapper;
        this.resumeProfileMapper = resumeProfileMapper;
        this.profileAnalysisMapper = profileAnalysisMapper;
        this.producer = producer;
        this.resumeProperties = resumeProperties;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 兼容已有测试或内部调用方；正式 Bean 使用包含 SchemaValidator 和事务管理器的构造函数。
     */
    public ProfileAnalysisTaskService(
            AiTaskMapper aiTaskMapper,
            ResumeProfileMapper resumeProfileMapper,
            ProfileAnalysisMapper profileAnalysisMapper,
            ProfileAnalyzeTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                aiTaskMapper,
                resumeProfileMapper,
                profileAnalysisMapper,
                producer,
                resumeProperties,
                objectMapper,
                null,
                transactionManager);
    }

    /**
     * 用户选择面试方向后触发该方向画像分析（幂等）。
     *
     * 流程：
     * 1. 校验方向、画像确认状态和简历向量已入库；
     * 2. 已存在同方向成功分析 → 直接返回 SUCCESS；
     * 3. 已存在进行中任务 → 返回任务状态（幂等）；
     * 4. 分析失败或异常终态 → 创建补偿任务并投递 MQ。
     *
     * 画像行锁保证并发触发串行化：连续两次点击只创建一个任务。
     */
    @Transactional
    public ProfileAnalysisStatusDto ensureTask(Long profileId, Long userId, String roleDirection) {
        validateDirection(roleDirection);
        ResumeProfile profile = getOwnedProfile(profileId, userId, true);
        requireConfirmed(profile);
        requireVectorizeCompleted(profile);

        // 已有成功分析：幂等返回，不再重复生成。
        ProfileAnalysis existing = findAnalysis(profile, roleDirection);
        if (existing != null) {
            log.info("画像分析已存在，幂等返回，profileId={}, version={}, direction={}, analysisId={}",
                    profile.getId(), profile.getVersion(), roleDirection, existing.getId());
            return toStatus(profile, roleDirection, findLatestTask(profile, roleDirection, false), existing);
        }

        AiTask latest = findLatestTask(profile, roleDirection, true);
        if (latest != null && isActive(latest)) {
            // 进行中任务幂等返回，避免并发创建多个任务。
            return toStatus(profile, roleDirection, latest, null);
        }
        // FAILED 只表示本次任务已经终态失败；再次触发时创建新的 taskId 作为补偿任务，
        // 保留旧任务记录便于审计。任务为 SUCCESS 但缺少分析数据属于异常终态，同样重新生成。
        if (latest != null) {
            log.info("当前画像分析任务已失败或缺少结果，创建补偿任务，profileId={}, version={}, direction={}, oldTaskId={}",
                    profile.getId(), profile.getVersion(), roleDirection, latest.getTaskId());
        }

        AiTask task = buildTask(profile, roleDirection);
        aiTaskMapper.insert(task);
        schedulePublishAfterCommit(task, profile, roleDirection);
        return toStatus(profile, roleDirection, task, null);
    }

    /**
     * 查询当前画像版本、当前方向的画像分析状态。
     *
     * 隔离条件只由当前登录用户和路径中的 profileId 生成，前端不能传入
     * user_id、profile_version 等字段参与查询。
     */
    @Transactional(readOnly = true)
    public ProfileAnalysisStatusDto getStatus(Long profileId, Long userId, String roleDirection) {
        validateDirection(roleDirection);
        ResumeProfile profile = getOwnedProfile(profileId, userId, false);
        requireConfirmed(profile);
        ProfileAnalysis existing = findAnalysis(profile, roleDirection);
        AiTask latest = findLatestTask(profile, roleDirection, false);
        return toStatus(profile, roleDirection, latest, existing);
    }

    /**
     * 画像分析失败后手工重试。
     *
     * 只有失败或尚未创建任务时允许重试，进行中/成功任务保持幂等返回。
     * 重试同样校验简历向量已入库，向量未就绪时不允许盲重试。
     */
    @Transactional
    public ProfileAnalysisStatusDto retry(Long profileId, Long userId, String roleDirection) {
        validateDirection(roleDirection);
        ResumeProfile profile = getOwnedProfile(profileId, userId, true);
        requireConfirmed(profile);
        requireVectorizeCompleted(profile);

        ProfileAnalysis existing = findAnalysis(profile, roleDirection);
        if (existing != null) {
            return toStatus(profile, roleDirection, findLatestTask(profile, roleDirection, false), existing);
        }

        AiTask latest = findLatestTask(profile, roleDirection, true);
        if (latest != null && isActive(latest)) {
            return toStatus(profile, roleDirection, latest, null);
        }

        AiTask task = buildTask(profile, roleDirection);
        aiTaskMapper.insert(task);
        schedulePublishAfterCommit(task, profile, roleDirection);
        return toStatus(profile, roleDirection, task, null);
    }

    /**
     * 消费 FastAPI 的画像分析结果。
     *
     * 成功时在任务行锁保护下写入 profile_analysis（按唯一键先查后插/更新），
     * 并更新 ai_task 为 SUCCESS；失败只更新 ai_task 为 FAILED，不写入分析数据。
     * 结果与画像版本、方向严格匹配，避免旧版本或错误方向的结果污染新状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleResult(ProfileAnalyzeResultMessage message) {
        validateResult(message);
        AiTask task = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, message.getTaskId())
                        // 结果消费需要和同一 taskId 的重复消息串行化。
                        .last("FOR UPDATE"));
        if (task == null) {
            throw new BusinessException("画像分析任务不存在");
        }
        validateTaskRelation(task, message);

        if (TaskStatus.SUCCESS.getCode().equals(task.getTaskStatus())
                || TaskStatus.FAILED.getCode().equals(task.getTaskStatus())) {
            // 终态任务的重复结果只记录并忽略，避免迟到消息覆盖审计数据。
            return;
        }

        boolean success = Boolean.TRUE.equals(message.getSuccess());
        if (success) {
            upsertAnalysis(task, message);
        }
        task.setTaskStatus(success
                ? TaskStatus.SUCCESS.getCode()
                : TaskStatus.FAILED.getCode());
        task.setRetryCount(message.getRetryCount());
        task.setErrorMessage(success ? null : message.getErrorMessage());
        task.setFinishedAt(LocalDateTime.now());
        task.setResultPayloadJson(toJson(message));
        aiTaskMapper.updateById(task);
    }

    /**
     * MQ 投递失败时将任务收口为 FAILED，画像与确认状态保持不变。
     *
     * 画像分析没有独立的补偿调度器（对比 RESUME_PARSE/RESUME_VECTORIZE），
     * 因此不能把任务置为 RETRYING 等待后台重投——那样用户重试会被 isActive
     * 幂等拦截，方向永久卡死。收口为 FAILED 后，ensureTask()/retry() 会创建
     * 新的补偿任务，用户可自助恢复。
     */
    public void markDispatchFailed(String taskId, String errorMessage) {
        if (transactionTemplate == null) {
            log.warn("当前画像分析服务未配置事务管理器，无法补偿 MQ 投递失败，taskId={}", taskId);
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            AiTask task = aiTaskMapper.selectOne(
                    new LambdaQueryWrapper<AiTask>()
                            .eq(AiTask::getTaskId, taskId)
                            .last("FOR UPDATE"));
            if (task == null
                    || TaskStatus.SUCCESS.getCode().equals(task.getTaskStatus())
                    || TaskStatus.FAILED.getCode().equals(task.getTaskStatus())) {
                return;
            }
            task.setTaskStatus(TaskStatus.FAILED.getCode());
            task.setErrorMessage(errorMessage);
            task.setFinishedAt(LocalDateTime.now());
            aiTaskMapper.updateById(task);
        });
    }

    /**
     * 将结果消费者无法安全处理的消息收口为最终失败。
     *
     * 结果消息进入 DLQ 前必须先释放前端的“处理中”状态；这里只更新 ai_task，
     * 不写入分析数据。REQUIRES_NEW 保证即使外层消费事务因业务异常回滚，
     * 补偿状态仍然能够提交。
     */
    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void markResultHandlingFailed(String taskId, String errorMessage) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("画像分析结果无法关联任务，跳过失败收口");
            return;
        }
        AiTask task = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, taskId)
                        .last("FOR UPDATE"));
        if (task == null
                || TaskStatus.SUCCESS.getCode().equals(task.getTaskStatus())
                || TaskStatus.FAILED.getCode().equals(task.getTaskStatus())) {
            return;
        }
        task.setTaskStatus(TaskStatus.FAILED.getCode());
        task.setErrorMessage(errorMessage);
        task.setFinishedAt(LocalDateTime.now());
        aiTaskMapper.updateById(task);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 校验面试方向取值，非法方向直接返回参数错误。
     */
    private RoleDirection validateDirection(String roleDirection) {
        RoleDirection direction = RoleDirection.fromCode(roleDirection);
        if (direction == null) {
            throw new BusinessException(ResponseCode.BAD_REQUEST, "不支持的面试方向：" + roleDirection);
        }
        return direction;
    }

    /**
     * 校验简历向量已成功入库。
     *
     * 画像分析依赖简历向量片段做语义检索；向量任务尚未 SUCCESS 时不创建
     * PROFILE_ANALYZE 任务，避免前端等待一次注定失败或空上下文的分析。
     */
    private void requireVectorizeCompleted(ResumeProfile profile) {
        AiTask vectorTask = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.RESUME_VECTORIZE.getCode())
                        .eq(AiTask::getBizType, BizType.RESUME_PROFILE.getCode())
                        .eq(AiTask::getBizId, profile.getId())
                        .eq(AiTask::getProfileVersion, profile.getVersion())
                        .eq(AiTask::getOperation, OPERATION_UPSERT)
                        .eq(AiTask::getTaskStatus, TaskStatus.SUCCESS.getCode())
                        .orderByDesc(AiTask::getId)
                        .last("LIMIT 1"));
        if (vectorTask == null) {
            throw new BusinessException(ResponseCode.CONFLICT,
                    "简历向量尚未入库完成，暂无法生成画像分析", HttpStatus.CONFLICT);
        }
    }

    /**
     * 查询当前画像版本、当前方向的画像分析结果。
     * MyBatis-Plus 逻辑删除已开启，自动过滤 deleted=1 的行。
     */
    private ProfileAnalysis findAnalysis(ResumeProfile profile, String roleDirection) {
        return profileAnalysisMapper.selectOne(
                new LambdaQueryWrapper<ProfileAnalysis>()
                        .eq(ProfileAnalysis::getResumeProfileId, profile.getId())
                        .eq(ProfileAnalysis::getRoleDirection, roleDirection)
                        .eq(ProfileAnalysis::getProfileVersion, profile.getVersion()));
    }

    /**
     * 查询当前画像版本、当前方向的最新 PROFILE_ANALYZE 任务。
     *
     * 面试方向记录在 ai_task.request_payload_json 中（无独立列），通过
     * MySQL JSON 函数过滤，避免把不同方向的任务混为一谈。任务极少，无需担心索引。
     */
    private AiTask findLatestTask(
            ResumeProfile profile, String roleDirection, boolean forUpdate) {
        String lockClause = forUpdate ? "LIMIT 1 FOR UPDATE" : "LIMIT 1";
        List<AiTask> tasks = aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.PROFILE_ANALYZE.getCode())
                        .eq(AiTask::getBizType, BizType.RESUME_PROFILE.getCode())
                        .eq(AiTask::getBizId, profile.getId())
                        .eq(AiTask::getProfileVersion, profile.getVersion())
                        .apply("JSON_UNQUOTE(JSON_EXTRACT(request_payload_json, '$.roleDirection')) = {0}",
                                roleDirection)
                        .orderByDesc(AiTask::getId)
                        .last(lockClause));
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    /**
     * 事务提交后投递 MQ，保证消息发送不随画像/任务状态回滚。
     */
    private void schedulePublishAfterCommit(
            AiTask task, ResumeProfile profile, String roleDirection) {
        Runnable publish = () -> {
            ProfileAnalyzeMessage message = ProfileAnalyzeMessage.builder()
                    .taskId(task.getTaskId())
                    .traceId(task.getTraceId())
                    .messageType(MESSAGE_TYPE)
                    .schemaVersion(SCHEMA_VERSION)
                    .retryCount(task.getRetryCount())
                    .createdAt(task.getCreatedAt() == null ? LocalDateTime.now() : task.getCreatedAt())
                    .resumeProfileId(String.valueOf(profile.getId()))
                    .roleDirection(roleDirection)
                    .profileVersion(profile.getVersion())
                    .vectorizeCompleted(true)
                    .build();
            boolean sent = false;
            try {
                sent = producer.sendWithRetry(
                        message,
                        resumeProperties.getMq().getMaxRetryAttempts(),
                        resumeProperties.getMq().getRetryBaseDelayMs());
            } catch (RuntimeException exception) {
                // sendWithRetry 只捕获 AmqpException；序列化等非 Amqp 异常会穿透到这里，
                // 必须收口为 FAILED，否则任务停留在已提交的 PENDING 无人接管。
                log.error("画像分析任务投递异常，taskId={}，error={}", task.getTaskId(), exception.getMessage());
            }
            if (!sent) {
                markDispatchFailed(task.getTaskId(), "RabbitMQ 暂时不可用，任务已标记失败，可重试");
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            // 兼容非事务调用方；触发和重试的正式入口均会走事务分支。
            publish.run();
        }
    }

    private AiTask buildTask(ResumeProfile profile, String roleDirection) {
        return AiTask.builder()
                .taskId(UUID.randomUUID().toString())
                .userId(profile.getUserId())
                .taskType(TaskType.PROFILE_ANALYZE.getCode())
                .taskStatus(TaskStatus.PENDING.getCode())
                .bizType(BizType.RESUME_PROFILE.getCode())
                .bizId(profile.getId())
                .profileVersion(profile.getVersion())
                .retryCount(0)
                .maxRetry(resumeProperties.getMq().getMaxScheduledRetryCount())
                .traceId(TraceIdContext.currentTraceId())
                .messageType(MESSAGE_TYPE)
                .schemaVersion(SCHEMA_VERSION)
                // roleDirection 是已校验的枚举值，无注入风险；记录在请求载荷中供任务查询与审计。
                .requestPayloadJson("{\"roleDirection\":\"" + roleDirection + "\"}")
                .build();
    }

    /**
     * 在任务行锁保护下写入或更新画像分析结果（幂等）。
     *
     * 先按唯一键查询（自定义 SQL 绕过逻辑删除过滤）：存在则更新内容并复活软删除行；
     * 不存在则插入。并发场景下两个任务可能同时通过"不存在"检查，插入冲突时捕获
     * DuplicateKeyException 并改为更新已存在行。
     */
    private void upsertAnalysis(AiTask task, ProfileAnalyzeResultMessage message) {
        Long resumeProfileId = parseProfileId(message.getResumeProfileId());
        ProfileAnalysis analysis = buildAnalysis(task, message);
        ProfileAnalysis existing = profileAnalysisMapper.selectForUpsert(
                resumeProfileId, message.getRoleDirection(), message.getProfileVersion());
        if (existing != null) {
            replaceAnalysis(existing.getId(), analysis);
            log.info("画像分析结果已更新，profileId={}, version={}, direction={}",
                    resumeProfileId, message.getProfileVersion(), message.getRoleDirection());
            return;
        }
        try {
            profileAnalysisMapper.insert(analysis);
            log.info("画像分析结果写入成功，profileId={}, version={}, direction={}, analysisId={}",
                    resumeProfileId, message.getProfileVersion(), message.getRoleDirection(), analysis.getId());
        } catch (DuplicateKeyException duplicate) {
            ProfileAnalysis winner = profileAnalysisMapper.selectForUpsert(
                    resumeProfileId, message.getRoleDirection(), message.getProfileVersion());
            if (winner == null) {
                throw duplicate;
            }
            replaceAnalysis(winner.getId(), analysis);
            log.info("画像分析结果并发冲突后已更新，profileId={}, version={}, direction={}",
                    resumeProfileId, message.getProfileVersion(), message.getRoleDirection());
        }
    }

    /**
     * 整体替换画像分析行内容。
     *
     * 使用 UpdateWrapper 显式设置全部字段（包括值为 null 的可选 JSON 列），
     * 避免 MyBatis-Plus 默认 NOT_NULL 更新策略跳过 null 字段，导致上一次分析
     * 的可选字段残留成陈旧数据。同时将软删除行复活（deleted=0）。
     */
    private void replaceAnalysis(Long analysisId, ProfileAnalysis analysis) {
        profileAnalysisMapper.update(
                null,
                new UpdateWrapper<ProfileAnalysis>()
                        .eq("id", analysisId)
                        .set("user_id", analysis.getUserId())
                        .set("resume_profile_id", analysis.getResumeProfileId())
                        .set("role_direction", analysis.getRoleDirection())
                        .set("skill_tags_json", analysis.getSkillTagsJson())
                        .set("project_graph_json", analysis.getProjectGraphJson())
                        .set("capability_hints_json", analysis.getCapabilityHintsJson())
                        .set("risk_points_json", analysis.getRiskPointsJson())
                        .set("suggested_topics_json", analysis.getSuggestedTopicsJson())
                        .set("stage_targets_json", analysis.getStageTargetsJson())
                        .set("profile_version", analysis.getProfileVersion())
                        .set("model_name", analysis.getModelName())
                        .set("model_version", analysis.getModelVersion())
                        .set("deleted", 0)
                        .set("updated_at", LocalDateTime.now()));
    }

    private ProfileAnalysis buildAnalysis(AiTask task, ProfileAnalyzeResultMessage message) {
        return ProfileAnalysis.builder()
                .userId(task.getUserId())
                .resumeProfileId(parseProfileId(message.getResumeProfileId()))
                .roleDirection(message.getRoleDirection())
                .skillTagsJson(jsonToString(message.getSkillTags()))
                .projectGraphJson(jsonToString(message.getProjectGraph()))
                .capabilityHintsJson(jsonToString(message.getCapabilityHints()))
                .riskPointsJson(jsonToString(message.getRiskPoints()))
                .suggestedTopicsJson(jsonToString(message.getSuggestedTopics()))
                .stageTargetsJson(jsonToString(message.getStageTargets()))
                .profileVersion(message.getProfileVersion())
                .modelName(message.getModelName())
                .modelVersion(message.getModelVersion())
                .build();
    }

    /**
     * 校验画像分析结果消息：契约校验 + 字段完整性 + 业务规则。
     */
    private void validateResult(ProfileAnalyzeResultMessage message) {
        if (message == null) {
            throw new BusinessException("画像分析结果消息不能为空");
        }
        if (schemaValidator != null) {
            try {
                schemaValidator.validateProfileAnalyzeResult(message);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("画像分析结果契约校验失败：" + exception.getMessage());
            }
        }
        if (message.getTaskId() == null
                || message.getTraceId() == null
                || message.getMessageType() == null
                || message.getSchemaVersion() == null
                || message.getRetryCount() == null
                || message.getCreatedAt() == null
                || message.getResumeProfileId() == null
                || message.getProfileVersion() == null
                || message.getRoleDirection() == null
                || message.getSuccess() == null) {
            throw new BusinessException("画像分析结果消息缺少必要字段");
        }
        if (!RESULT_MESSAGE_TYPE.equals(message.getMessageType())
                || !SCHEMA_VERSION.equals(message.getSchemaVersion())) {
            throw new BusinessException("画像分析结果消息类型或版本不正确");
        }
        if (RoleDirection.fromCode(message.getRoleDirection()) == null) {
            throw new BusinessException("画像分析结果面试方向不正确");
        }
        if (message.getRetryCount() < 0
                || message.getRetryCount() > resumeProperties.getMq().getMaxScheduledRetryCount()) {
            throw new BusinessException("画像分析结果重试次数超出允许范围");
        }
        if (!Boolean.TRUE.equals(message.getSuccess())
                && (message.getErrorMessage() == null || message.getErrorMessage().isBlank())) {
            throw new BusinessException("画像分析失败结果缺少错误原因");
        }
    }

    /**
     * 校验结果消息与任务记录的关联关系，防止伪造或跨用户/跨版本/跨方向消息。
     */
    private void validateTaskRelation(AiTask task, ProfileAnalyzeResultMessage message) {
        if (!TaskType.PROFILE_ANALYZE.getCode().equals(task.getTaskType())
                || !BizType.RESUME_PROFILE.getCode().equals(task.getBizType())
                || task.getBizId() == null
                || task.getProfileVersion() == null
                || !String.valueOf(task.getBizId()).equals(message.getResumeProfileId())
                || !Integer.valueOf(task.getProfileVersion()).equals(message.getProfileVersion())
                || task.getTraceId() == null
                || !task.getTraceId().equals(message.getTraceId())) {
            throw new BusinessException("画像分析结果与任务画像不匹配");
        }
        String taskDirection = readRoleDirectionFromTask(task);
        if (taskDirection != null && !taskDirection.equals(message.getRoleDirection())) {
            throw new BusinessException("画像分析结果面试方向与任务不匹配");
        }
    }

    /**
     * 从任务请求载荷读取面试方向；载荷缺失或无法解析时返回 null（不做方向强校验）。
     */
    private String readRoleDirectionFromTask(AiTask task) {
        String payload = task.getRequestPayloadJson();
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(payload).get("roleDirection");
            return node == null || node.isNull() ? null : node.asText();
        } catch (JsonProcessingException exception) {
            log.warn("画像分析任务请求载荷解析失败，无法读取面试方向，taskId={}", task.getTaskId());
            return null;
        }
    }

    private ResumeProfile getOwnedProfile(Long profileId, Long userId, boolean forUpdate) {
        LambdaQueryWrapper<ResumeProfile> wrapper = new LambdaQueryWrapper<ResumeProfile>()
                .eq(ResumeProfile::getId, profileId);
        if (forUpdate) {
            wrapper.last("FOR UPDATE");
        }
        ResumeProfile profile = resumeProfileMapper.selectOne(wrapper);
        if (profile == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "简历画像不存在");
        }
        if (!userId.equals(profile.getUserId())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该简历画像", HttpStatus.FORBIDDEN);
        }
        return profile;
    }

    private void requireConfirmed(ResumeProfile profile) {
        if (!ConfirmStatus.CONFIRMED.getCode().equals(profile.getConfirmStatus())) {
            throw new BusinessException(ResponseCode.CONFLICT, "请先确认简历画像", HttpStatus.CONFLICT);
        }
    }

    /**
     * 是否进行中：PENDING / PROCESSING / RETRYING。
     * SUCCESS 不在此列——若任务已 SUCCESS 但缺少分析数据属于异常终态，需重新生成。
     */
    private boolean isActive(AiTask task) {
        String status = task.getTaskStatus();
        return TaskStatus.PENDING.getCode().equals(status)
                || TaskStatus.PROCESSING.getCode().equals(status)
                || TaskStatus.RETRYING.getCode().equals(status);
    }

    /**
     * 组装状态响应：已有分析数据时以 SUCCESS 为准，否则透传任务状态。
     */
    private ProfileAnalysisStatusDto toStatus(
            ResumeProfile profile, String roleDirection, AiTask task, ProfileAnalysis analysis) {
        if (analysis != null) {
            return ProfileAnalysisStatusDto.builder()
                    .profileAnalysisId(String.valueOf(analysis.getId()))
                    .profileId(String.valueOf(profile.getId()))
                    .profileVersion(profile.getVersion())
                    .roleDirection(roleDirection)
                    .taskId(task == null ? null : task.getTaskId())
                    .status(TaskStatus.SUCCESS.getCode())
                    .retryCount(task == null ? 0 : task.getRetryCount())
                    .updatedAt(analysis.getUpdatedAt())
                    .build();
        }
        if (task == null) {
            // 尚未创建任务：状态为 PENDING，前端可触发创建。
            return ProfileAnalysisStatusDto.builder()
                    .profileId(String.valueOf(profile.getId()))
                    .profileVersion(profile.getVersion())
                    .roleDirection(roleDirection)
                    .status(TaskStatus.PENDING.getCode())
                    .retryCount(0)
                    .build();
        }
        return ProfileAnalysisStatusDto.builder()
                .profileId(String.valueOf(profile.getId()))
                .profileVersion(profile.getVersion())
                .roleDirection(roleDirection)
                .taskId(task.getTaskId())
                .status(task.getTaskStatus())
                .retryCount(task.getRetryCount())
                .errorMessage(task.getErrorMessage())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private Long parseProfileId(String profileId) {
        try {
            return Long.parseLong(profileId);
        } catch (NumberFormatException exception) {
            throw new BusinessException("简历画像 ID 格式非法：" + profileId);
        }
    }

    private String jsonToString(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            log.warn("画像分析结果 JSON 序列化失败，返回 null", exception);
            return null;
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("画像分析结果序列化失败，仍保存任务状态", exception);
            return null;
        }
    }
}
