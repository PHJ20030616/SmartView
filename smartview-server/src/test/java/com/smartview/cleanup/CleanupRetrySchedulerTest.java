package com.smartview.cleanup;

import com.smartview.common.enums.BizType;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清理任务补偿调度器测试（Task 7.2 清理失败可重试）。
 *
 * 覆盖：可重试任务按租约抢占并重新投递、超过最大重试次数收口终态失败、
 * 抢占失败不做任何投递。
 */
@ExtendWith(MockitoExtension.class)
class CleanupRetrySchedulerTest {

    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private CleanupTaskProducer producer;

    private CleanupRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        ResumeProperties resumeProperties = new ResumeProperties();
        resumeProperties.getMq().setMaxScheduledRetryCount(3);
        scheduler = new CleanupRetryScheduler(
                aiTaskMapper,
                producer,
                new CleanupTaskService(
                        aiTaskMapper,
                        producer,
                        resumeProperties,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        null),
                resumeProperties);
    }

    private AiTask cleanupTask(TaskStatus status, String taskId, int retryCount) {
        return AiTask.builder()
                .taskId(taskId)
                .userId(7L)
                .taskType(TaskType.CLEANUP.getCode())
                .taskStatus(status.getCode())
                .bizType(BizType.RESUME_FILE.getCode())
                .bizId(88L)
                .traceId("trace-1")
                .retryCount(retryCount)
                .maxRetry(3)
                .messageType("CLEANUP_TASK")
                .schemaVersion("1.0.0")
                .requestPayloadJson("{\"objectKey\":\"resumes/7/a.pdf\","
                        + "\"resumeProfileIds\":[\"101\"]}")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now().minusMinutes(10))
                .build();
    }

    @Test
    void retryFailedCleanupTasks_republishesRetryableTask() {
        AiTask task = cleanupTask(TaskStatus.FAILED, "t1", 1);
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        // 抢占成功
        when(aiTaskMapper.update(isNull(), any())).thenReturn(1);

        scheduler.retryFailedCleanupTasks();

        ArgumentCaptor<CleanupMessage> messageCaptor = ArgumentCaptor.forClass(CleanupMessage.class);
        verify(producer).send(messageCaptor.capture());
        CleanupMessage message = messageCaptor.getValue();
        assertThat(message.getTaskId()).isEqualTo("t1");
        // 重试次数递增后投递
        assertThat(message.getRetryCount()).isEqualTo(2);
        assertThat(message.getObjectKey()).isEqualTo("resumes/7/a.pdf");
        assertThat(message.getResumeProfileIds()).containsExactly("101");
    }

    @Test
    void retryFailedCleanupTasks_marksFinalFailedWhenRetryExhausted() {
        AiTask task = cleanupTask(TaskStatus.FAILED, "t1", 3);
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        // 达到 maxRetry=3，不再投递，直接标记最终失败
        when(aiTaskMapper.update(isNull(), any())).thenReturn(1);

        scheduler.retryFailedCleanupTasks();

        verify(producer, never()).send(any(CleanupMessage.class));
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper> updateCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(aiTaskMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getParamNameValuePairs().values())
                .contains(TaskStatus.FAILED.getCode());
    }

    @Test
    void retryFailedCleanupTasks_skipsTaskWhenClaimFails() {
        AiTask task = cleanupTask(TaskStatus.RETRYING, "t1", 1);
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        // 抢占失败：其他实例已处理或状态已变化
        when(aiTaskMapper.update(isNull(), any())).thenReturn(0);

        scheduler.retryFailedCleanupTasks();

        verify(producer, never()).send(any(CleanupMessage.class));
    }

    @Test
    void retryFailedCleanupTasks_doesNothingWhenNoTasks() {
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());

        scheduler.retryFailedCleanupTasks();

        verify(producer, never()).send(any(CleanupMessage.class));
    }
}
