package com.smartview.resume.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.smartview.common.enums.ParseStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.infra.minio.MinioService;
import com.smartview.resume.entity.ResumeFile;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeFileMapper;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeTaskProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeFileServiceTest {

    @Mock
    private ResumeFileMapper resumeFileMapper;

    @Mock
    private ResumeProfileMapper resumeProfileMapper;

    @Mock
    private AiTaskMapper aiTaskMapper;

    @Mock
    private ResumeVectorizationService resumeVectorizationService;

    @Mock
    private MinioService minioService;

    @Mock
    private ResumeTaskProducer resumeTaskProducer;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ResumeProperties resumeProperties;
    private ResumeFileService service;

    @BeforeEach
    void setUp() {
        resumeProperties = new ResumeProperties();
        resumeProperties.getMq().setMaxRetryAttempts(1);
        resumeProperties.getMq().setRetryBaseDelayMs(0L);
        resumeProperties.getMq().setMaxScheduledRetryCount(3);

        // afterCommit 回调依赖独立事务；单元测试用同步执行模拟 TransactionTemplate 的新事务边界。
        lenient().doAnswer(invocation -> {
            Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service = new ResumeFileService(
                resumeFileMapper,
                resumeProfileMapper,
                aiTaskMapper,
                minioService,
                resumeTaskProducer,
                resumeProperties,
                resumeVectorizationService,
                transactionTemplate
        );
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void afterCommit_shouldMoveInitialTaskToRetryingWhenMessageWasSent() {
        AtomicReference<AiTask> createdTask = stubUploadRecords();
        MockMultipartFile file = validPdf();
        when(resumeTaskProducer.sendResumeParseTaskWithRetry(any(), anyInt(), anyLong()))
                .thenReturn(true);
        when(minioService.generatePresignedUrl("resumes/7/resume.pdf", 1))
                .thenReturn("https://minio.example/resume.pdf");

        service.uploadResume(file, 7L);
        runAfterCommitCallbacks();

        ArgumentCaptor<UpdateWrapper<AiTask>> taskUpdateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(aiTaskMapper).update(isNull(), taskUpdateCaptor.capture());
        UpdateWrapper<AiTask> taskUpdate = taskUpdateCaptor.getValue();

        assertThat(taskUpdate.getSqlSegment())
                .contains("task_status")
                .contains("retry_count")
                .contains("finished_at");
        assertThat(taskUpdate.getSqlSet()).contains("task_status");
        assertThat(taskUpdate.getParamNameValuePairs().values())
                .contains(TaskStatus.PENDING.getCode(), TaskStatus.RETRYING.getCode());
        assertThat(createdTask.get().getTaskStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        verify(resumeTaskProducer).sendResumeParseTaskWithRetry(any(), eq(1), eq(0L));
    }

    @Test
    void afterCommit_shouldKeepPendingFileWhenInitialMessageSendFails() {
        AtomicReference<AiTask> createdTask = stubUploadRecords();
        MockMultipartFile file = validPdf();
        when(resumeTaskProducer.sendResumeParseTaskWithRetry(any(), anyInt(), anyLong()))
                .thenReturn(false);
        when(aiTaskMapper.selectOne(any())).thenAnswer(invocation -> createdTask.get());
        when(aiTaskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        service.uploadResume(file, 7L);
        runAfterCommitCallbacks();

        ArgumentCaptor<UpdateWrapper<AiTask>> taskUpdateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(aiTaskMapper).update(isNull(), taskUpdateCaptor.capture());
        assertThat(taskUpdateCaptor.getValue().getSqlSegment())
                .contains("task_status")
                .contains("retry_count")
                .contains("finished_at")
                .contains("error_message");
        assertThat(taskUpdateCaptor.getValue().getParamNameValuePairs().values())
                .contains(TaskStatus.RETRYING.getCode());

        ArgumentCaptor<UpdateWrapper<ResumeFile>> fileUpdateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(resumeFileMapper).update(isNull(), fileUpdateCaptor.capture());
        assertThat(fileUpdateCaptor.getValue().getParamNameValuePairs().values())
                .contains(ParseStatus.PENDING.getCode());
    }

    @Test
    void deleteResume_shouldSoftDeleteAllProfilesAndScheduleDerivedDataCleanup() {
        ResumeFile resumeFile = ResumeFile.builder()
                .id(88L)
                .userId(7L)
                .objectKey("resumes/7/old-resume.pdf")
                .build();
        ResumeProfile firstProfile = ResumeProfile.builder()
                .id(101L)
                .userId(7L)
                .resumeFileId(88L)
                .version(1)
                .build();
        ResumeProfile secondProfile = ResumeProfile.builder()
                .id(102L)
                .userId(7L)
                .resumeFileId(88L)
                .version(2)
                .build();
        when(resumeFileMapper.selectOne(any())).thenReturn(resumeFile);
        when(resumeProfileMapper.selectList(any()))
                .thenReturn(List.of(firstProfile, secondProfile));

        service.deleteResume(88L, 7L);

        verify(resumeVectorizationService).ensureDeleteTask(firstProfile);
        verify(resumeVectorizationService).ensureDeleteTask(secondProfile);
        verify(resumeProfileMapper).deleteById(101L);
        verify(resumeProfileMapper).deleteById(102L);
        verify(resumeFileMapper).deleteById(88L);
        verify(minioService, never()).deleteFile(any());

        runAfterCommitCallbacks();

        verify(minioService).deleteFile("resumes/7/old-resume.pdf");
    }

    @Test
    void deleteResume_shouldNotDeleteWhenFileDoesNotBelongToCurrentUser() {
        when(resumeFileMapper.selectOne(any())).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.deleteResume(88L, 99L))
                .isInstanceOf(com.smartview.common.exception.BusinessException.class)
                .hasMessage("简历文件不存在");

        verify(resumeProfileMapper, never()).selectList(any());
        verify(resumeFileMapper, never()).deleteById(anyLong());
        verify(resumeVectorizationService, never()).ensureDeleteTask(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "RETRYING",
            "FAILED",
            "SUCCESS"
    })
    void afterCommit_shouldNotOverwriteTaskAlreadyHandledByAnotherFlow(String status) {
        AtomicReference<AiTask> createdTask = stubUploadRecords();
        MockMultipartFile file = validPdf();
        AiTask currentTask = AiTask.builder()
                .taskId("created-task")
                .bizId(101L)
                .taskStatus(status)
                .retryCount(TaskStatus.SUCCESS.getCode().equals(status) ? 1 : 0)
                .maxRetry(3)
                .build();
        when(resumeTaskProducer.sendResumeParseTaskWithRetry(any(), anyInt(), anyLong()))
                .thenReturn(false);
        when(aiTaskMapper.selectOne(any())).thenReturn(currentTask);
        when(aiTaskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        service.uploadResume(file, 7L);
        runAfterCommitCallbacks();

        ArgumentCaptor<UpdateWrapper<AiTask>> taskUpdateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(aiTaskMapper).update(isNull(), taskUpdateCaptor.capture());
        assertThat(taskUpdateCaptor.getValue().getSqlSegment())
                .contains("task_status")
                .contains("retry_count")
                .contains("finished_at")
                .contains("error_message");
        verify(resumeFileMapper, never()).update(isNull(), any(UpdateWrapper.class));
        assertThat(currentTask.getTaskStatus()).isEqualTo(status);
        assertThat(createdTask.get().getTaskStatus()).isEqualTo(TaskStatus.PENDING.getCode());
    }

    private AtomicReference<AiTask> stubUploadRecords() {
        AtomicReference<AiTask> createdTask = new AtomicReference<>();
        when(minioService.uploadResumeFile(any(), eq(7L)))
                .thenReturn("resumes/7/resume.pdf");
        doAnswer(invocation -> {
            ResumeFile resumeFile = invocation.getArgument(0);
            resumeFile.setId(101L);
            return 1;
        }).when(resumeFileMapper).insert(any(ResumeFile.class));
        doAnswer(invocation -> {
            createdTask.set(invocation.getArgument(0));
            return 1;
        }).when(aiTaskMapper).insert(any(AiTask.class));
        return createdTask;
    }

    private MockMultipartFile validPdf() {
        return new MockMultipartFile(
                "file",
                "resume.pdf",
                "application/pdf",
                "简历内容".getBytes(StandardCharsets.UTF_8)
        );
    }

    private void runAfterCommitCallbacks() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
    }
}
