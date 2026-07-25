package com.smartview.task.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartview.common.enums.ParseStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.infra.minio.MinioService;
import com.smartview.resume.entity.ResumeFile;
import com.smartview.resume.mapper.ResumeFileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeTaskProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ResumeParseRetrySchedulerTest {

    @Mock
    private AiTaskMapper aiTaskMapper;

    @Mock
    private ResumeFileMapper resumeFileMapper;

    @Mock
    private ResumeTaskProducer resumeTaskProducer;

    @Mock
    private MinioService minioService;

    private ResumeProperties resumeProperties;
    private ResumeParseRetryScheduler scheduler;

    @BeforeEach
    void setUp() {
        resumeProperties = new ResumeProperties();
        resumeProperties.getMq().setMaxScheduledRetryCount(3);
        lenient().when(aiTaskMapper.update(isNull(), any())).thenReturn(1);
        scheduler = new ResumeParseRetryScheduler(
                aiTaskMapper,
                resumeFileMapper,
                resumeTaskProducer,
                minioService,
                resumeProperties
        );
    }

    @Test
    void scheduledRetry_shouldFinalizeTaskWhenResumeFileIsMissing() {
        AiTask task = failedTask(0);
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(resumeFileMapper.selectById(99L)).thenReturn(null);

        scheduler.retryFailedParseTasks();

        assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(task.getRetryCount()).isEqualTo(task.getMaxRetry());
        assertThat(task.getFinishedAt()).isNotNull();
        assertThat(task.getErrorMessage()).contains("简历文件不存在");
        // 关联文件分支必须使用带租约条件的原子更新，不能回写旧实体。
        verify(aiTaskMapper, atLeastOnce()).update(isNull(), any(UpdateWrapper.class));
        verify(aiTaskMapper, never()).updateById(task);
    }

    @Test
    void scheduledRetry_shouldKeepFailedStatusWhenRetryLimitIsReached() {
        AiTask task = failedTask(2);
        ResumeFile resumeFile = ResumeFile.builder()
                .id(99L)
                .objectKey("resumes/99/resume.pdf")
                .mimeType("application/pdf")
                .build();
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(resumeFileMapper.selectById(99L)).thenReturn(resumeFile);
        when(minioService.generatePresignedUrl(resumeFile.getObjectKey(), 1))
                .thenThrow(new IllegalStateException("对象存储不可用"));

        scheduler.retryFailedParseTasks();

        assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(task.getRetryCount()).isEqualTo(3);
        assertThat(task.getFinishedAt()).isNotNull();
        assertThat(task.getErrorMessage()).contains("最大重试次数");
        verify(aiTaskMapper, atLeastOnce()).update(isNull(), any());
    }

    @Test
    void scheduledRetry_shouldKeepRetryingWhenMessageSendFailsBeforeLimit() {
        AiTask task = failedTask(0);
        ResumeFile resumeFile = ResumeFile.builder()
                .id(99L)
                .objectKey("resumes/99/resume.pdf")
                .mimeType("application/pdf")
                .build();
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(resumeFileMapper.selectById(99L)).thenReturn(resumeFile);
        when(minioService.generatePresignedUrl(resumeFile.getObjectKey(), 1))
                .thenReturn("https://minio.example/resume.pdf");
        doThrow(new IllegalStateException("MQ 暂时不可用"))
                .when(resumeTaskProducer).sendResumeParseTask(any());

        scheduler.retryFailedParseTasks();

        assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.RETRYING.getCode());
        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.getFinishedAt()).isNull();
        assertThat(resumeFile.getParseStatus()).isEqualTo(ParseStatus.PENDING.getCode());
        verify(aiTaskMapper, atLeastOnce()).update(isNull(), any());
    }

    @Test
    void scheduledRetry_shouldNotOverwriteSuccessWrittenByConsumerAfterSend() {
        AiTask task = failedTask(0);
        ResumeFile resumeFile = ResumeFile.builder()
                .id(99L)
                .objectKey("resumes/99/resume.pdf")
                .mimeType("application/pdf")
                .build();
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(resumeFileMapper.selectById(99L)).thenReturn(resumeFile);
        when(minioService.generatePresignedUrl(resumeFile.getObjectKey(), 1))
                .thenReturn("https://minio.example/resume.pdf");
        doAnswer(invocation -> {
            // 模拟消费者在发送返回前完成处理，验证调度器不会用旧实体覆盖 SUCCESS。
            task.setTaskStatus(TaskStatus.SUCCESS.getCode());
            task.setFinishedAt(LocalDateTime.now());
            return null;
        }).when(resumeTaskProducer).sendResumeParseTask(any());

        scheduler.retryFailedParseTasks();

        assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.getCode());
        assertThat(task.getFinishedAt()).isNotNull();
        verify(resumeFileMapper, never()).updateById(any(ResumeFile.class));
    }

    @Test
    void scheduledRetry_shouldRecoverStalePendingTask() {
        AiTask task = failedTask(0);
        task.setTaskStatus(TaskStatus.PENDING.getCode());
        task.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        ResumeFile resumeFile = ResumeFile.builder()
                .id(99L)
                .objectKey("resumes/99/resume.pdf")
                .mimeType("application/pdf")
                .build();
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(resumeFileMapper.selectById(99L)).thenReturn(resumeFile);
        when(minioService.generatePresignedUrl(resumeFile.getObjectKey(), 1))
                .thenReturn("https://minio.example/resume.pdf");

        scheduler.retryFailedParseTasks();

        verify(resumeTaskProducer).sendResumeParseTask(any());
        verify(aiTaskMapper, atLeastOnce()).update(isNull(), any());
    }

    @Test
    void scheduledRetry_shouldRequireLeaseTimeoutForRetryingTasks() {
        AiTask task = failedTask(1);
        task.setTaskStatus(TaskStatus.RETRYING.getCode());
        task.setUpdatedAt(LocalDateTime.now());
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());

        scheduler.retryFailedParseTasks();

        ArgumentCaptor<LambdaQueryWrapper<AiTask>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiTaskMapper).selectList(wrapperCaptor.capture());
        // 真实数据库会根据查询条件过滤未超过租约时长的 RETRYING 任务。
        verify(resumeTaskProducer, never()).sendResumeParseTask(any());
        verify(aiTaskMapper, never()).update(isNull(), any());
    }

    @Test
    void scheduledRetry_shouldNotWriteFileAfterAnotherInstanceTakesOverLease() {
        AiTask task = failedTask(0);
        ResumeFile resumeFile = ResumeFile.builder()
                .id(99L)
                .objectKey("resumes/99/resume.pdf")
                .mimeType("application/pdf")
                .build();
        AiTask newerLease = failedTask(1);
        newerLease.setTaskStatus(TaskStatus.RETRYING.getCode());
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(aiTaskMapper.selectOne(any())).thenReturn(task, newerLease);
        // 第一次更新抢占任务，第二次续租，第三次才是异常回写；异常回写返回 0 表示租约已被接管。
        when(aiTaskMapper.update(isNull(), any())).thenReturn(1, 1, 0);
        when(resumeFileMapper.selectById(99L)).thenReturn(resumeFile);
        when(resumeFileMapper.update(isNull(), any())).thenReturn(1);
        when(minioService.generatePresignedUrl(resumeFile.getObjectKey(), 1))
                .thenThrow(new IllegalStateException("对象存储不可用"));

        scheduler.retryFailedParseTasks();

        verify(resumeFileMapper, times(1)).update(isNull(), any());
        ArgumentCaptor<UpdateWrapper<AiTask>> wrapperCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(aiTaskMapper, times(3)).update(isNull(), wrapperCaptor.capture());

        // 续租更新也会包含 retry_count，但只有异常回写同时设置 error_message。
        UpdateWrapper<AiTask> failureUpdate = wrapperCaptor.getAllValues().stream()
                .filter(wrapper -> wrapper.getSqlSet() != null
                        && wrapper.getSqlSegment() != null
                        && wrapper.getSqlSegment().contains("error_message"))
                .findFirst()
                .orElseThrow();
        assertThat(failureUpdate.getSqlSegment())
                .contains("retry_count")
                .contains("error_message");
        assertThat(failureUpdate.getSqlSet())
                .contains("updated_at");
    }

    @Test
    void scheduledRetry_shouldNotFinalizeLeaseTakenTask() {
        AiTask task = failedTask(1);
        AiTask newerLease = failedTask(3);
        newerLease.setTaskStatus(TaskStatus.RETRYING.getCode());

        when(aiTaskMapper.selectList(any())).thenReturn(List.of(task));
        // 第一次查询是本实例抢占前的快照，第二次查询是抢占失败后的新租约快照。
        when(aiTaskMapper.selectOne(any())).thenReturn(task, newerLease, newerLease);
        when(aiTaskMapper.update(isNull(), any())).thenReturn(0);

        scheduler.retryFailedParseTasks();

        // 旧实例不能把其他实例仍在处理的 RETRYING 任务收口成 FAILED。
        verify(aiTaskMapper, times(1)).update(isNull(), any(UpdateWrapper.class));
        assertThat(newerLease.getTaskStatus()).isEqualTo(TaskStatus.RETRYING.getCode());
    }

    private AiTask failedTask(int retryCount) {
        return AiTask.builder()
                .taskId("task-001")
                .taskStatus(TaskStatus.FAILED.getCode())
                .taskType("RESUME_PARSE")
                .bizId(99L)
                .retryCount(retryCount)
                .maxRetry(3)
                .build();
    }
}
