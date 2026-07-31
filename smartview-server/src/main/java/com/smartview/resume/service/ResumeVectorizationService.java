package com.smartview.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.common.validation.SchemaValidator;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.resume.dto.ResumeVectorizationStatusDto;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeVectorTaskProducer;
import com.smartview.task.mq.ResumeVectorizeMessage;
import com.smartview.task.mq.ResumeVectorizeResultMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 简历向量入库任务服务。
 *
 * 关键一致性规则：
 * 1. 画像确认状态和任务记录在同一个 MySQL 事务中提交；
 * 2. MQ 只在事务提交后发送，MQ/Chroma/Redis 异常不能回滚 CONFIRMED；
 * 3. 任务和结果必须同时匹配画像 ID、画像版本，旧版本结果只能被忽略；
 * 4. 手工重试创建新 taskId，保留旧任务审计记录，避免迟到结果污染新任务。
 */
@Slf4j
@Service
public class ResumeVectorizationService {

    private static final String MESSAGE_TYPE = "RESUME_VECTORIZE_TASK";
    private static final String RESULT_MESSAGE_TYPE = "RESUME_VECTORIZE_RESULT";
    private static final String SCHEMA_VERSION = "1.0.0";
    private static final String OPERATION_UPSERT = "UPSERT";
    private static final String OPERATION_DELETE = "DELETE";

    private final AiTaskMapper aiTaskMapper;
    private final ResumeProfileMapper resumeProfileMapper;
    private final ResumeVectorTaskProducer producer;
    private final ResumeProperties resumeProperties;
    private final ObjectMapper objectMapper;
    private final SchemaValidator schemaValidator;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ResumeVectorizationService(
            AiTaskMapper aiTaskMapper,
            ResumeProfileMapper resumeProfileMapper,
            ResumeVectorTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            SchemaValidator schemaValidator,
            PlatformTransactionManager transactionManager) {
        this.aiTaskMapper = aiTaskMapper;
        this.resumeProfileMapper = resumeProfileMapper;
        this.producer = producer;
        this.resumeProperties = resumeProperties;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 兼容已有测试或内部调用方；正式 Bean 使用包含 SchemaValidator 的构造函数。
     */
    public ResumeVectorizationService(
            AiTaskMapper aiTaskMapper,
            ResumeProfileMapper resumeProfileMapper,
            ResumeVectorTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                aiTaskMapper,
                resumeProfileMapper,
                producer,
                resumeProperties,
                objectMapper,
                null,
                transactionManager);
    }

    /**
     * 保留测试和旧调用方使用的构造函数；正式 Spring Bean 使用上面的事务管理器构造函数。
     */
    public ResumeVectorizationService(
            AiTaskMapper aiTaskMapper,
            ResumeProfileMapper resumeProfileMapper,
            ResumeVectorTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper) {
        this.aiTaskMapper = aiTaskMapper;
        this.resumeProfileMapper = resumeProfileMapper;
        this.producer = producer;
        this.resumeProperties = resumeProperties;
        this.objectMapper = objectMapper;
        this.schemaValidator = null;
        this.transactionTemplate = null;
    }

    /**
     * 为已确认画像确保一个当前版本任务。
     *
     * 该方法必须在确认事务中调用；任务记录先写入 MySQL，消息在事务提交后发送。
     */
    @Transactional
    public AiTask ensureTask(ResumeProfile profile) {
        requireConfirmed(profile);
        AiTask existing = findLatestTask(
                profile.getId(), profile.getVersion(), OPERATION_UPSERT, true);
        if (existing != null && isActiveOrSuccessful(existing)) {
            return existing;
        }
        // FAILED 只表示本次任务已经终态失败；确认接口再次幂等调用时，
        // 创建一个新的 taskId 作为补偿任务，保留旧任务记录便于审计。
        if (existing != null) {
            log.info("当前画像向量任务已失败，创建补偿任务，profileId={}, version={}, oldTaskId={}",
                    profile.getId(), profile.getVersion(), existing.getTaskId());
        }

        AiTask task = buildTask(profile, OPERATION_UPSERT);
        aiTaskMapper.insert(task);
        schedulePublishAfterCommit(task, profile, OPERATION_UPSERT);
        return task;
    }

    /**
     * 为已删除画像创建幂等的向量清理任务。
     *
     * <p>删除任务允许作用于未确认画像，因为删除动作的目标是清理可能已经
     * 写入过的历史向量，而不是向量化未确认内容。任务记录在软删除事务内创建，
     * 消息在事务提交后发送。</p>
     */
    public AiTask ensureDeleteTask(ResumeProfile profile) {
        AiTask existing = findLatestTask(
                profile.getId(), profile.getVersion(), OPERATION_DELETE, true);
        if (existing != null && isActiveOrSuccessful(existing)) {
            return existing;
        }

        AiTask task = buildTask(profile, OPERATION_DELETE);
        aiTaskMapper.insert(task);
        schedulePublishAfterCommit(task, profile, OPERATION_DELETE);
        return task;
    }

    /**
     * 手工重试当前画像版本的向量入库。
     *
     * 画像行锁和当前任务状态检查必须在同一事务内完成，防止用户连续点击重试
     * 产生多个同时写入 Chroma 的任务。
     */
    @Transactional
    public ResumeVectorizationStatusDto retry(Long profileId, Long userId) {
        ResumeProfile profile = getOwnedProfile(profileId, userId, true);
        requireConfirmed(profile);

        AiTask latest = findLatestTask(
                profile.getId(), profile.getVersion(), OPERATION_UPSERT, true);
        if (latest != null && isActiveOrSuccessful(latest)) {
            return toStatus(profile, latest);
        }

        AiTask task = buildTask(profile, OPERATION_UPSERT);
        aiTaskMapper.insert(task);
        schedulePublishAfterCommit(task, profile, OPERATION_UPSERT);
        return toStatus(profile, task);
    }

    /**
     * 查询当前画像版本的向量入库状态。
     *
     * 隔离条件只由当前登录用户和路径中的 profileId 生成，前端不能传入
     * user_id、profile_version 等字段参与查询。
     */
    @Transactional(readOnly = true)
    public ResumeVectorizationStatusDto getStatus(Long profileId, Long userId) {
        ResumeProfile profile = getOwnedProfile(profileId, userId, false);
        requireConfirmed(profile);
        return toStatus(
                profile,
                findLatestTask(profile.getId(), profile.getVersion(), OPERATION_UPSERT, false));
    }

    /**
     * 消费 FastAPI 的向量结果。
     *
     * 结果失败只更新 ai_task，不修改 resume_profile.confirm_status；因此 Chroma
     * 或 Redis 临时异常不会改变 MySQL 中已经确认的权威画像状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleResult(ResumeVectorizeResultMessage message) {
        // operation 是 1.0.0 契约中的兼容字段；旧 worker 未返回时按 UPSERT 处理，
        // 但后续仍会和任务记录做严格匹配，避免 DELETE 结果误写入 UPSERT 任务。
        normalizeResultOperation(message);
        validateResult(message);
        AiTask task = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, message.getTaskId())
                        // 结果消费需要和同一 taskId 的重复消息串行化。
                        .last("FOR UPDATE"));
        if (task == null) {
            throw new BusinessException("向量入库任务不存在");
        }
        validateTaskRelation(task, message);

        if (TaskStatus.SUCCESS.getCode().equals(task.getTaskStatus())
                || TaskStatus.FAILED.getCode().equals(task.getTaskStatus())) {
            // 终态任务的重复结果只记录并忽略，避免迟到消息覆盖审计数据。
            return;
        }

        task.setTaskStatus(Boolean.TRUE.equals(message.getSuccess())
                ? TaskStatus.SUCCESS.getCode()
                : TaskStatus.FAILED.getCode());
        task.setRetryCount(message.getRetryCount());
        task.setErrorMessage(Boolean.TRUE.equals(message.getSuccess()) ? null : message.getErrorMessage());
        task.setFinishedAt(LocalDateTime.now());
        try {
            task.setResultPayloadJson(objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException exception) {
            log.warn("向量结果序列化失败，仍保存任务状态，taskId={}", message.getTaskId(), exception);
        }
        aiTaskMapper.updateById(task);
    }

    /**
     * MQ 投递失败时仅将任务标记为 RETRYING，确认状态保持不变。
     */
    public void markDispatchFailed(String taskId, String errorMessage) {
        if (transactionTemplate == null) {
            log.warn("当前向量服务未配置事务管理器，无法补偿 MQ 投递失败，taskId={}", taskId);
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
            task.setTaskStatus(TaskStatus.RETRYING.getCode());
            task.setErrorMessage(errorMessage);
            task.setFinishedAt(null);
            aiTaskMapper.updateById(task);
        });
    }

    /**
     * 将结果消费者无法安全处理的消息收口为最终失败。
     *
     * 结果消息进入 DLQ 前必须先释放前端的“处理中”状态；这里只更新 ai_task，
     * 不回滚或修改 MySQL 中已经确认的简历画像。REQUIRES_NEW 保证即使外层消费事务
     * 因业务异常回滚，补偿状态仍然能够提交。
     */
    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void markResultHandlingFailed(String taskId, String errorMessage) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("向量结果无法关联任务，跳过失败收口");
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

    private void schedulePublishAfterCommit(
            AiTask task, ResumeProfile profile, String operation) {
        Runnable publish = () -> {
            ResumeVectorizeMessage message = ResumeVectorizeMessage.builder()
                    .taskId(task.getTaskId())
                    .traceId(task.getTraceId())
                    .messageType(MESSAGE_TYPE)
                    .schemaVersion(SCHEMA_VERSION)
                    .retryCount(task.getRetryCount())
                    .createdAt(task.getCreatedAt() == null ? LocalDateTime.now() : task.getCreatedAt())
                    .resumeProfileId(String.valueOf(profile.getId()))
                    .profileVersion(profile.getVersion())
                    .operation(operation)
                    .build();
            boolean sent = producer.sendWithRetry(
                    message,
                    resumeProperties.getMq().getMaxRetryAttempts(),
                    resumeProperties.getMq().getRetryBaseDelayMs());
            if (!sent) {
                markDispatchFailed(task.getTaskId(), "RabbitMQ 暂时不可用，任务等待补偿重试");
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
            // 兼容非事务调用方；确认和重试的正式入口均会走事务分支。
            publish.run();
        }
    }

    private AiTask buildTask(ResumeProfile profile, String operation) {
        String taskId = UUID.randomUUID().toString();
        String traceId = TraceIdContext.currentTraceId();
        return AiTask.builder()
                .taskId(taskId)
                .userId(profile.getUserId())
                .taskType(TaskType.RESUME_VECTORIZE.getCode())
                .taskStatus(TaskStatus.PENDING.getCode())
                .bizType(BizType.RESUME_PROFILE.getCode())
                .bizId(profile.getId())
                .profileVersion(profile.getVersion())
                .operation(operation)
                .retryCount(0)
                .maxRetry(resumeProperties.getMq().getMaxScheduledRetryCount())
                .traceId(traceId)
                .messageType(MESSAGE_TYPE)
                .schemaVersion(SCHEMA_VERSION)
                .build();
    }

    private AiTask findLatestTask(
            Long profileId,
            Integer profileVersion,
            String operation,
            boolean forUpdate) {
        String lockClause = forUpdate ? "LIMIT 1 FOR UPDATE" : "LIMIT 1";
        List<AiTask> tasks = aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.RESUME_VECTORIZE.getCode())
                        .eq(AiTask::getBizType, BizType.RESUME_PROFILE.getCode())
                        .eq(AiTask::getBizId, profileId)
                        .eq(AiTask::getProfileVersion, profileVersion)
                        .eq(AiTask::getOperation, operation)
                        .orderByDesc(AiTask::getId)
                        .last(lockClause));
        return tasks.isEmpty() ? null : tasks.get(0);
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
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该简历画像");
        }
        return profile;
    }

    private boolean isActiveOrSuccessful(AiTask task) {
        String status = task.getTaskStatus();
        return TaskStatus.PENDING.getCode().equals(status)
                || TaskStatus.PROCESSING.getCode().equals(status)
                || TaskStatus.RETRYING.getCode().equals(status)
                || TaskStatus.SUCCESS.getCode().equals(status);
    }

    private void requireConfirmed(ResumeProfile profile) {
        if (!ConfirmStatus.CONFIRMED.getCode().equals(profile.getConfirmStatus())) {
            throw new BusinessException(ResponseCode.CONFLICT, "请先确认简历画像");
        }
    }

    private void validateResult(ResumeVectorizeResultMessage message) {
        if (message == null) {
            throw new BusinessException("向量入库结果消息不能为空");
        }
        if (schemaValidator != null) {
            try {
                schemaValidator.validateResumeVectorizeResult(message);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("向量入库结果契约校验失败：" + exception.getMessage());
            }
        }
        if (message == null
                || message.getTaskId() == null
                || message.getTraceId() == null
                || message.getMessageType() == null
                || message.getSchemaVersion() == null
                || message.getRetryCount() == null
                || message.getCreatedAt() == null
                || message.getResumeProfileId() == null
                || message.getProfileVersion() == null
                || message.getOperation() == null
                || message.getSuccess() == null) {
            throw new BusinessException("向量入库结果消息缺少必要字段");
        }
        if (!RESULT_MESSAGE_TYPE.equals(message.getMessageType())
                || !SCHEMA_VERSION.equals(message.getSchemaVersion())) {
            throw new BusinessException("向量入库结果消息类型或版本不正确");
        }
        if (!OPERATION_UPSERT.equals(message.getOperation())
                && !OPERATION_DELETE.equals(message.getOperation())) {
            throw new BusinessException("向量入库结果操作类型不正确");
        }
        if (message.getRetryCount() < 0
                || message.getRetryCount() > resumeProperties.getMq().getMaxScheduledRetryCount()) {
            throw new BusinessException("向量入库结果重试次数超出允许范围");
        }
        if (Boolean.TRUE.equals(message.getSuccess())
                && (message.getChunksCount() == null || message.getChunksCount() < 0)) {
            throw new BusinessException("向量入库成功结果缺少有效切片数量");
        }
        if (!Boolean.TRUE.equals(message.getSuccess())
                && (message.getErrorMessage() == null || message.getErrorMessage().isBlank())) {
            throw new BusinessException("向量入库失败结果缺少错误原因");
        }
    }

    private void validateTaskRelation(AiTask task, ResumeVectorizeResultMessage message) {
        String taskOperation = task.getOperation() == null
                ? OPERATION_UPSERT
                : task.getOperation();
        if (!TaskType.RESUME_VECTORIZE.getCode().equals(task.getTaskType())
                || !BizType.RESUME_PROFILE.getCode().equals(task.getBizType())
                || task.getBizId() == null
                || task.getProfileVersion() == null
                || !String.valueOf(task.getBizId()).equals(message.getResumeProfileId())
                || !Integer.valueOf(task.getProfileVersion()).equals(message.getProfileVersion())
                || !taskOperation.equals(message.getOperation())
                || task.getTraceId() == null
                || !task.getTraceId().equals(message.getTraceId())) {
            throw new BusinessException("向量入库结果与任务画像不匹配");
        }
    }

    /**
     * 兼容旧版本 FastAPI 结果消息缺少 operation 的情况。
     *
     * <p>只有缺失值可以兼容为 UPSERT，未知字符串仍由 validateResult 拒绝，
     * 防止把未来操作类型静默当成写入操作。</p>
     */
    private void normalizeResultOperation(ResumeVectorizeResultMessage message) {
        if (message != null && (message.getOperation() == null || message.getOperation().isBlank())) {
            message.setOperation(OPERATION_UPSERT);
        }
    }

    private ResumeVectorizationStatusDto toStatus(
            ResumeProfile profile, AiTask task) {
        if (task == null) {
            return ResumeVectorizationStatusDto.builder()
                    .resumeProfileId(String.valueOf(profile.getId()))
                    .profileVersion(profile.getVersion())
                    .status(TaskStatus.PENDING.getCode())
                    .retryCount(0)
                    .build();
        }
        return ResumeVectorizationStatusDto.builder()
                .resumeProfileId(String.valueOf(profile.getId()))
                .profileVersion(profile.getVersion())
                .taskId(task.getTaskId())
                .status(task.getTaskStatus())
                .retryCount(task.getRetryCount())
                .chunksCount(readChunksCount(task.getResultPayloadJson()))
                .errorMessage(task.getErrorMessage())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private Integer readChunksCount(String resultPayloadJson) {
        if (resultPayloadJson == null || resultPayloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(resultPayloadJson).get("chunksCount");
            return node == null || node.isNull() ? null : node.asInt();
        } catch (JsonProcessingException exception) {
            log.warn("向量任务结果 JSON 解析失败，无法读取切片数量", exception);
            return null;
        }
    }
}
