package com.smartview.resume.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简历向量入库任务服务测试。
 *
 * 覆盖：markDispatchFailed 在独立事务（REQUIRES_NEW）中把任务标记 RETRYING、终态任务忽略，
 * 以及 ensureTask 幂等跳过既有进行中任务。
 */
@ExtendWith(MockitoExtension.class)
class ResumeVectorizationServiceTest {

    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private ResumeProfileMapper resumeProfileMapper;
    @Mock
    private ResumeVectorTaskProducer producer;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ResumeVectorizationService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        // 测试构造函数不注入 SchemaValidator（消息校验由契约测试另行覆盖），
        // 走 7 参构造函数委托到带事务管理器的 @Autowired 构造函数，模板强制 REQUIRES_NEW。
        service = new ResumeVectorizationService(
                aiTaskMapper,
                resumeProfileMapper,
                producer,
                new ResumeProperties(),
                objectMapper,
                null,
                transactionManager);
    }

    private ResumeProfile confirmedProfile() {
        return ResumeProfile.builder()
                .id(12L)
                .userId(7L)
                .resumeFileId(3L)
                .version(2)
                .confirmStatus(ConfirmStatus.CONFIRMED.getCode())
                .build();
    }

    private AiTask vectorizeTask(TaskStatus status, String taskId) {
        return AiTask.builder()
                .taskId(taskId)
                .userId(7L)
                .taskType(TaskType.RESUME_VECTORIZE.getCode())
                .taskStatus(status.getCode())
                .bizType(BizType.RESUME_PROFILE.getCode())
                .bizId(12L)
                .profileVersion(2)
                .operation("UPSERT")
                .traceId("trace")
                .retryCount(0)
                .maxRetry(3)
                .build();
    }

    // ==================== markDispatchFailed ====================

    @Test
    void markDispatchFailed_updatesTaskToRetryingInIndependentTransaction() {
        AiTask task = vectorizeTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        // 模拟 REQUIRES_NEW 事务：getTransaction 返回新事务状态，模板提交时独立 commit。
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        service.markDispatchFailed("t1", "MQ 暂时不可用");

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.RETRYING.getCode());
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("MQ 暂时不可用");
        // REQUIRES_NEW 模板在 afterCommit 回调中独立开启并提交，RETRYING 更新真正落库，
        // 后续由 ResumeVectorRetryScheduler 按租约抢占补偿。
        verify(transactionManager).commit(status);
    }

    @Test
    void markDispatchFailed_ignoresTerminalTask() {
        AiTask task = vectorizeTask(TaskStatus.SUCCESS, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        service.markDispatchFailed("t1", "MQ 暂时不可用");

        // 终态任务不覆盖审计数据，仅独立事务内空提交。
        verify(aiTaskMapper, never()).updateById(any(AiTask.class));
        verify(transactionManager).commit(status);
    }

    // ==================== ensureTask ====================

    @Test
    void ensureTask_returnsExistingTaskWhenAlreadyInProgress() {
        AiTask running = vectorizeTask(TaskStatus.PROCESSING, "t-running");
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(running));

        AiTask result = service.ensureTask(confirmedProfile());

        assertThat(result.getTaskId()).isEqualTo("t-running");
        verify(aiTaskMapper, never()).insert(any(AiTask.class));
    }
}
