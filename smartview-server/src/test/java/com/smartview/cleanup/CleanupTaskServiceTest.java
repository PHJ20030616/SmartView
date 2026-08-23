package com.smartview.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清理任务服务测试（Task 7.2 软删除与物理清理）。
 *
 * 覆盖：清理任务创建与事务提交后投递、同业务任务幂等复用、结果回写终态、
 * 终态重复结果忽略、结果与任务关系校验、投递失败标记 RETRYING。
 */
@ExtendWith(MockitoExtension.class)
class CleanupTaskServiceTest {

    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private CleanupTaskProducer producer;
    @Mock
    private PlatformTransactionManager transactionManager;

    private CleanupTaskService service;
    private ResumeProperties resumeProperties;

    @BeforeEach
    void setUp() {
        resumeProperties = new ResumeProperties();
        resumeProperties.getMq().setMaxRetryAttempts(1);
        resumeProperties.getMq().setRetryBaseDelayMs(0L);
        resumeProperties.getMq().setMaxScheduledRetryCount(3);
        // 测试构造函数不注入 SchemaValidator（契约校验由 SchemaValidatorTest 覆盖），
        // 走 6 参构造函数委托到带事务管理器的 @Autowired 构造函数，模板强制 REQUIRES_NEW。
        // ObjectMapper 注册 JSR310 模块，保证 LocalDateTime 结果载荷可序列化
        // （生产环境由 Spring Boot 自动注册，单测需手动补齐）。
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new CleanupTaskService(
                aiTaskMapper,
                producer,
                resumeProperties,
                objectMapper,
                transactionManager);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void runAfterCommitCallbacks() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }

    private AiTask cleanupTask(TaskStatus status, String taskId) {
        return AiTask.builder()
                .taskId(taskId)
                .userId(7L)
                .taskType(TaskType.CLEANUP.getCode())
                .taskStatus(status.getCode())
                .bizType(BizType.RESUME_FILE.getCode())
                .bizId(88L)
                .traceId("trace-1")
                .retryCount(0)
                .maxRetry(3)
                .messageType("CLEANUP_TASK")
                .schemaVersion("1.0.0")
                .requestPayloadJson("{\"objectKey\":\"resumes/7/a.pdf\","
                        + "\"resumeProfileIds\":[\"101\",\"102\"]}")
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== ensureCleanupTask ====================

    @Test
    void ensureCleanupTask_createsTaskAndPublishesAfterCommit() {
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());

        service.ensureCleanupTask(88L, "resumes/7/a.pdf", List.of(101L, 102L), 7L);

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).insert(taskCaptor.capture());
        AiTask task = taskCaptor.getValue();
        assertThat(task.getTaskType()).isEqualTo(TaskType.CLEANUP.getCode());
        assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(task.getBizType()).isEqualTo(BizType.RESUME_FILE.getCode());
        assertThat(task.getBizId()).isEqualTo(88L);
        assertThat(task.getRequestPayloadJson()).contains("resumes/7/a.pdf")
                .contains("101").contains("102");

        // 提交前不投递；afterCommit 回调中才发送
        verify(producer, never()).sendWithRetry(any(), any(Integer.class), any(Long.class));
        runAfterCommitCallbacks();

        ArgumentCaptor<CleanupMessage> messageCaptor = ArgumentCaptor.forClass(CleanupMessage.class);
        verify(producer).sendWithRetry(messageCaptor.capture(), any(Integer.class), any(Long.class));
        CleanupMessage message = messageCaptor.getValue();
        assertThat(message.getTaskId()).isEqualTo(task.getTaskId());
        assertThat(message.getObjectKey()).isEqualTo("resumes/7/a.pdf");
        assertThat(message.getResumeProfileIds()).containsExactly("101", "102");
    }

    @Test
    void ensureCleanupTask_reusesExistingActiveTask() {
        AiTask existing = cleanupTask(TaskStatus.PENDING, "t-existing");
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(existing));

        service.ensureCleanupTask(88L, "resumes/7/a.pdf", List.of(101L), 7L);

        verify(aiTaskMapper, never()).insert(any(AiTask.class));
        verify(producer, never()).sendWithRetry(any(), any(Integer.class), any(Long.class));
    }

    @Test
    void ensureCleanupTask_requiresBizId() {
        assertThatThrownBy(() -> service.ensureCleanupTask(null, "k", List.of(101L), 7L))
                .isInstanceOf(com.smartview.common.exception.BusinessException.class)
                .hasMessage("清理任务缺少业务 ID");
    }

    @Test
    void ensureCleanupTask_marksRetryingWhenPublishFailsAfterCommit() {
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());
        when(producer.sendWithRetry(any(), any(Integer.class), any(Long.class))).thenReturn(false);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        // markDispatchFailed 在 REQUIRES_NEW 事务内按 taskId 重新查询任务
        when(aiTaskMapper.selectOne(any())).thenReturn(cleanupTask(TaskStatus.PENDING, "t1"));

        service.ensureCleanupTask(88L, "resumes/7/a.pdf", List.of(101L), 7L);
        runAfterCommitCallbacks();

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.RETRYING.getCode());
        // REQUIRES_NEW 模板独立提交，RETRYING 更新真正落库，等待 CleanupRetryScheduler 补偿
        verify(transactionManager).commit(status);
    }

    // ==================== handleResult ====================

    @Test
    void handleResult_successUpdatesTaskToSuccessWithResultPayload() {
        AiTask task = cleanupTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);

        service.handleResult(cleanupResult(true, "t1", 2, null));

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.getCode());
        assertThat(taskCaptor.getValue().getFinishedAt()).isNotNull();
        assertThat(taskCaptor.getValue().getResultPayloadJson()).contains("\"success\":true");
    }

    @Test
    void handleResult_failureUpdatesTaskToFailedWithError() {
        AiTask task = cleanupTask(TaskStatus.RETRYING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);

        service.handleResult(cleanupResult(false, "t1", null, "MinIO 不可用"));

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("MinIO 不可用");
    }

    @Test
    void handleResult_ignoresDuplicateResultOnTerminalTask() {
        AiTask task = cleanupTask(TaskStatus.SUCCESS, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);

        service.handleResult(cleanupResult(false, "t1", null, "迟到失败结果"));

        // 终态任务的重复结果只记录并忽略，避免迟到消息覆盖审计数据
        verify(aiTaskMapper, never()).updateById(any(AiTask.class));
    }

    @Test
    void handleResult_rejectsRelationMismatch() {
        AiTask task = cleanupTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);

        CleanupResultMessage wrong = cleanupResult(true, "t1", 1, null);
        wrong.setBizId("999");

        assertThatThrownBy(() -> service.handleResult(wrong))
                .isInstanceOf(com.smartview.common.exception.BusinessException.class)
                .hasMessageContaining("清理结果与任务业务不匹配");
        verify(aiTaskMapper, never()).updateById(any(AiTask.class));
    }

    @Test
    void handleResult_requiresTaskFound() {
        when(aiTaskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.handleResult(cleanupResult(true, "t-missing", 1, null)))
                .isInstanceOf(com.smartview.common.exception.BusinessException.class)
                .hasMessage("清理任务不存在");
    }

    // ==================== markDispatchFailed / rebuildMessage ====================

    @Test
    void markDispatchFailed_updatesTaskToRetryingInIndependentTransaction() {
        AiTask task = cleanupTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        service.markDispatchFailed("t1", "RabbitMQ 暂时不可用");

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.RETRYING.getCode());
        verify(transactionManager).commit(status);
    }

    @Test
    void rebuildMessage_restoresObjectKeyAndProfileIdsFromRequestPayload() {
        AiTask task = cleanupTask(TaskStatus.RETRYING, "t1");

        CleanupMessage message = service.rebuildMessage(task);

        assertThat(message.getObjectKey()).isEqualTo("resumes/7/a.pdf");
        assertThat(message.getResumeProfileIds()).containsExactly("101", "102");
        assertThat(message.getBizId()).isEqualTo("88");
        assertThat(message.getMessageType()).isEqualTo("CLEANUP_TASK");
    }

    private CleanupResultMessage cleanupResult(
            boolean success, String taskId, Integer cleanedCount, String errorMessage) {
        return CleanupResultMessage.builder()
                .taskId(taskId)
                .traceId("trace-1")
                .messageType("CLEANUP_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                // 结果消息 createdAt 为 RFC 3339 字符串（与生产 worker 透传一致）
                .createdAt(LocalDateTime.now().toString())
                .bizType(BizType.RESUME_FILE.getCode())
                .bizId("88")
                .success(success)
                .cleanedProfileCount(cleanedCount)
                .errorMessage(errorMessage)
                .build();
    }
}
