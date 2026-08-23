package com.smartview.cleanup;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.common.validation.SchemaValidator;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 清理任务服务（Task 7.2 软删除与物理清理）。
 *
 * <p>职责：在业务软删除事务内创建 CLEANUP 类型的 ai_task（作为审计记录），
 * 事务提交后向 MQ 投递清理任务；FastAPI cleanup worker 删除 MinIO 对象与
 * Chroma 向量后回传结果，由 {@link #handleResult(CleanupResultMessage)} 更新任务终态。</p>
 *
 * <p>关键一致性规则（沿用向量任务模式）：</p>
 * <ol>
 *   <li>任务记录与业务软删除在同一个 MySQL 事务中提交，保证删除后必有可追踪的清理动作；</li>
 *   <li>MQ 只在事务提交后发送，MinIO/Chroma/MQ 异常不能回滚 MySQL 的权威删除状态；</li>
 *   <li>清理目标是派生数据，允许最终补偿：任务失败由 CleanupRetryScheduler 依据
 *       ai_task 租约重新投递，重试耗尽后保持 FAILED 并保留 error_message 供运维审计。</li>
 * </ol>
 */
@Slf4j
@Service
public class CleanupTaskService {

    private static final String MESSAGE_TYPE = "CLEANUP_TASK";
    private static final String SCHEMA_VERSION = "1.0.0";

    private final AiTaskMapper aiTaskMapper;
    private final CleanupTaskProducer producer;
    private final ResumeProperties resumeProperties;
    private final ObjectMapper objectMapper;
    private final SchemaValidator schemaValidator;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public CleanupTaskService(
            AiTaskMapper aiTaskMapper,
            CleanupTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            SchemaValidator schemaValidator,
            PlatformTransactionManager transactionManager) {
        this.aiTaskMapper = aiTaskMapper;
        this.producer = producer;
        this.resumeProperties = resumeProperties;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // afterCommit 回调中外层事务已 doCommit 但尚未 cleanup，REQUIRED 会把模板加入
        // 已提交的"幻影事务"导致 UPDATE 不落库；强制 REQUIRES_NEW 保证补偿状态真正提交。
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 兼容测试或内部调用方；正式 Bean 使用包含 SchemaValidator 的构造函数。
     */
    public CleanupTaskService(
            AiTaskMapper aiTaskMapper,
            CleanupTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(aiTaskMapper, producer, resumeProperties, objectMapper, null, transactionManager);
    }

    /**
     * 在软删除事务内为业务数据创建清理任务（幂等）。
     *
     * <p>任务记录先写入 MySQL，消息在事务提交后发送；若同业务已存在未终态或已成功的
     * 清理任务则直接复用，避免重复删除。请求载荷（objectKey + 画像 ID 列表）写入
     * request_payload_json，既是审计信息，也是补偿调度重建消息的唯一事实来源。</p>
     *
     * @param bizId     业务 ID（当前为 resume_file.id）
     * @param objectKey MinIO 对象 Key
     * @param profileIds 需清理 Chroma 向量的简历画像 ID 列表
     * @param userId    所属用户 ID
     * @return 清理任务实体
     */
    @Transactional(rollbackFor = Exception.class)
    public AiTask ensureCleanupTask(
            Long bizId, String objectKey, List<Long> profileIds, Long userId) {
        if (bizId == null) {
            throw new BusinessException("清理任务缺少业务 ID");
        }
        AiTask existing = findLatestTask(bizId);
        if (existing != null && isActiveOrSuccessful(existing)) {
            log.info("清理任务已存在，直接复用，taskId={}, bizId={}", existing.getTaskId(), bizId);
            return existing;
        }

        AiTask task = buildTask(bizId, objectKey, profileIds, userId);
        aiTaskMapper.insert(task);
        schedulePublishAfterCommit(task, objectKey, profileIds);
        return task;
    }

    /**
     * 消费 FastAPI 的清理结果。
     *
     * <p>结果失败只更新 ai_task（FAILED + error_message），不修改已软删除的业务行；
     * MinIO/Chroma 的临时异常不会回滚 MySQL 中已经提交的删除状态。终态任务的重复结果
     * 只记录并忽略，避免迟到消息覆盖审计数据。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleResult(CleanupResultMessage message) {
        validateResult(message);
        AiTask task = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, message.getTaskId())
                        // 同一 taskId 的重复结果消息需要串行化处理
                        .last("FOR UPDATE"));
        if (task == null) {
            throw new BusinessException("清理任务不存在");
        }
        validateTaskRelation(task, message);

        if (TaskStatus.SUCCESS.getCode().equals(task.getTaskStatus())
                || TaskStatus.FAILED.getCode().equals(task.getTaskStatus())) {
            // 终态任务的重复结果只记录并忽略，避免迟到消息覆盖审计数据
            log.info("清理任务已处于终态，忽略重复结果，taskId={}", task.getTaskId());
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
            log.warn("清理结果序列化失败，仍保存任务状态，taskId={}", message.getTaskId(), exception);
        }
        aiTaskMapper.updateById(task);
        log.info("清理任务状态已更新，taskId={}, status={}, success={}",
                task.getTaskId(), task.getTaskStatus(), message.getSuccess());
    }

    /**
     * MQ 投递失败时仅将任务标记为 RETRYING，等待补偿调度重新投递。
     *
     * <p>本方法常在 schedulePublishAfterCommit 的 afterCommit 回调中执行：此时外层事务
     * 已 doCommit 但尚未 cleanupAfterCompletion，REQUIRED 会把模板加入已提交的"幻影事务"，
     * isNewTransaction=false 导致 UPDATE 不落库被静默回滚。因此构造函数中事务模板已强制
     * REQUIRES_NEW，确保在 afterCommit 回调中独立开启并提交新事务。</p>
     */
    public void markDispatchFailed(String taskId, String errorMessage) {
        if (transactionTemplate == null) {
            log.warn("当前清理服务未配置事务管理器，无法补偿 MQ 投递失败，taskId={}", taskId);
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
     * <p>结果消息进入 DLQ 前先把任务标记为 FAILED，避免清理任务永久停留在非终态
     * 且无人补偿。REQUIRES_NEW 保证即使外层消费事务因业务异常回滚，补偿状态仍然提交。</p>
     */
    @Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void markResultHandlingFailed(String taskId, String errorMessage) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("清理结果无法关联任务，跳过失败收口");
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

    /**
     * 供补偿调度器重建清理消息：从任务记录还原 objectKey 与画像 ID 列表。
     *
     * <p>对象存储与向量库是外部依赖，消息可能已丢失，因此调度器必须以
     * ai_task.request_payload_json 为唯一事实来源重建消息，而不是依赖 MQ 内容。</p>
     */
    public CleanupMessage rebuildMessage(AiTask task) {
        JsonNode payload = parseRequestPayload(task.getRequestPayloadJson());
        String objectKey = payload == null || !payload.hasNonNull("objectKey")
                ? null : payload.get("objectKey").asText();
        List<String> profileIds = new ArrayList<>();
        JsonNode profileIdsNode = payload == null ? null : payload.get("resumeProfileIds");
        if (profileIdsNode != null && profileIdsNode.isArray()) {
            // 逐元素取值：findValuesAsText("") 对数组节点不生效，会静默返回空列表
            for (JsonNode idNode : profileIdsNode) {
                profileIds.add(idNode.asText());
            }
        }

        return CleanupMessage.builder()
                .taskId(task.getTaskId())
                .traceId(task.getTraceId())
                .messageType(task.getMessageType() == null ? MESSAGE_TYPE : task.getMessageType())
                .schemaVersion(task.getSchemaVersion() == null ? SCHEMA_VERSION : task.getSchemaVersion())
                .retryCount(task.getRetryCount() == null ? 0 : task.getRetryCount())
                .createdAt(task.getCreatedAt() == null ? LocalDateTime.now() : task.getCreatedAt())
                .bizType(task.getBizType())
                .bizId(String.valueOf(task.getBizId()))
                .objectKey(objectKey)
                .resumeProfileIds(profileIds)
                .build();
    }

    private void schedulePublishAfterCommit(AiTask task, String objectKey, List<Long> profileIds) {
        Runnable publish = () -> {
            CleanupMessage message = CleanupMessage.builder()
                    .taskId(task.getTaskId())
                    .traceId(task.getTraceId())
                    .messageType(MESSAGE_TYPE)
                    .schemaVersion(SCHEMA_VERSION)
                    .retryCount(task.getRetryCount())
                    .createdAt(task.getCreatedAt() == null ? LocalDateTime.now() : task.getCreatedAt())
                    .bizType(task.getBizType())
                    .bizId(String.valueOf(task.getBizId()))
                    .objectKey(objectKey)
                    .resumeProfileIds(profileIds.stream().map(String::valueOf).collect(Collectors.toList()))
                    .build();
            boolean sent = producer.sendWithRetry(
                    message,
                    resumeProperties.getMq().getMaxRetryAttempts(),
                    resumeProperties.getMq().getRetryBaseDelayMs());
            if (!sent) {
                markDispatchFailed(task.getTaskId(), "RabbitMQ 暂时不可用，清理任务等待补偿重试");
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // afterCommit 回调中的异常会被 Spring 重抛给 DELETE 接口调用方，
                    // 导致"返回 500 但删除已提交"的假失败；这里兜底捕获，
                    // 剩余工作交给 CleanupRetryScheduler 依据 ai_task 状态补偿。
                    try {
                        publish.run();
                    } catch (Exception exception) {
                        log.error("清理任务提交后投递异常，等待补偿调度接管，taskId={}",
                                task.getTaskId(), exception);
                    }
                }
            });
        } else {
            // 兼容非事务调用方；简历删除的正式入口始终走事务分支
            publish.run();
        }
    }

    private AiTask buildTask(Long bizId, String objectKey, List<Long> profileIds, Long userId) {
        String taskId = UUID.randomUUID().toString();
        String traceId = TraceIdContext.currentTraceId();
        String requestPayload = buildRequestPayloadJson(objectKey, profileIds);
        return AiTask.builder()
                .taskId(taskId)
                .userId(userId)
                .taskType(TaskType.CLEANUP.getCode())
                .taskStatus(TaskStatus.PENDING.getCode())
                .bizType(BizType.RESUME_FILE.getCode())
                .bizId(bizId)
                .retryCount(0)
                .maxRetry(resumeProperties.getMq().getMaxScheduledRetryCount())
                .traceId(traceId)
                .messageType(MESSAGE_TYPE)
                .schemaVersion(SCHEMA_VERSION)
                .requestPayloadJson(requestPayload)
                .build();
    }

    /**
     * 把清理目标写入请求载荷：既是审计信息，也是补偿调度重建消息的唯一事实来源。
     */
    private String buildRequestPayloadJson(String objectKey, List<Long> profileIds) {
        try {
            return objectMapper.writeValueAsString(
                    java.util.Map.of(
                            "objectKey", objectKey,
                            "resumeProfileIds",
                            profileIds.stream().map(String::valueOf).collect(Collectors.toList())));
        } catch (JsonProcessingException exception) {
            log.warn("清理任务请求载荷序列化失败，将仅保存任务状态，bizId={}", profileIds, exception);
            return null;
        }
    }

    private JsonNode parseRequestPayload(String requestPayloadJson) {
        if (requestPayloadJson == null || requestPayloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(requestPayloadJson);
        } catch (JsonProcessingException exception) {
            log.warn("清理任务请求载荷解析失败，无法重建消息", exception);
            return null;
        }
    }

    private AiTask findLatestTask(Long bizId) {
        List<AiTask> tasks = aiTaskMapper.selectList(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.CLEANUP.getCode())
                        .eq(AiTask::getBizType, BizType.RESUME_FILE.getCode())
                        .eq(AiTask::getBizId, bizId)
                        .orderByDesc(AiTask::getId)
                        .last("LIMIT 1"));
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    private boolean isActiveOrSuccessful(AiTask task) {
        String status = task.getTaskStatus();
        return TaskStatus.PENDING.getCode().equals(status)
                || TaskStatus.PROCESSING.getCode().equals(status)
                || TaskStatus.RETRYING.getCode().equals(status)
                || TaskStatus.SUCCESS.getCode().equals(status);
    }

    private void validateResult(CleanupResultMessage message) {
        if (message == null) {
            throw new BusinessException("清理结果消息不能为空");
        }
        if (schemaValidator != null) {
            try {
                schemaValidator.validateCleanupResult(message);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("清理结果契约校验失败：" + exception.getMessage());
            }
        }
        if (message.getTaskId() == null
                || message.getTraceId() == null
                || message.getMessageType() == null
                || message.getSchemaVersion() == null
                || message.getRetryCount() == null
                || message.getCreatedAt() == null
                || message.getBizType() == null
                || message.getBizId() == null
                || message.getSuccess() == null) {
            throw new BusinessException("清理结果消息缺少必要字段");
        }
        if (!"CLEANUP_RESULT".equals(message.getMessageType())
                || !SCHEMA_VERSION.equals(message.getSchemaVersion())) {
            throw new BusinessException("清理结果消息类型或版本不正确");
        }
        if (!BizType.RESUME_FILE.getCode().equals(message.getBizType())) {
            throw new BusinessException("清理结果业务类型不正确");
        }
        if (message.getRetryCount() < 0
                || message.getRetryCount() > resumeProperties.getMq().getMaxScheduledRetryCount()) {
            throw new BusinessException("清理结果重试次数超出允许范围");
        }
        if (Boolean.TRUE.equals(message.getSuccess())
                && (message.getCleanedProfileCount() == null || message.getCleanedProfileCount() < 0)) {
            throw new BusinessException("清理成功结果缺少有效画像清理数量");
        }
        if (!Boolean.TRUE.equals(message.getSuccess())
                && (message.getErrorMessage() == null || message.getErrorMessage().isBlank())) {
            throw new BusinessException("清理失败结果缺少错误原因");
        }
    }

    private void validateTaskRelation(AiTask task, CleanupResultMessage message) {
        if (!TaskType.CLEANUP.getCode().equals(task.getTaskType())
                || !BizType.RESUME_FILE.getCode().equals(task.getBizType())
                || task.getBizId() == null
                || !String.valueOf(task.getBizId()).equals(message.getBizId())
                || !BizType.RESUME_FILE.getCode().equals(message.getBizType())
                || task.getTraceId() == null
                || !task.getTraceId().equals(message.getTraceId())) {
            throw new BusinessException(ResponseCode.CONFLICT, "清理结果与任务业务不匹配");
        }
    }
}
