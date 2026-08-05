package com.smartview.resume.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.profile.entity.ProfileAnalysis;
import com.smartview.profile.mapper.ProfileAnalysisMapper;
import com.smartview.resume.dto.ProfileAnalysisStatusDto;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ProfileAnalyzeResultMessage;
import com.smartview.task.mq.ProfileAnalyzeTaskProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 画像分析任务服务测试。
 *
 * 覆盖：向量入库前置校验、幂等（成功/进行中）、失败重试补偿、结果成功落库、
 * 结果失败仅更新任务，以及状态查询透传。
 */
@ExtendWith(MockitoExtension.class)
class ProfileAnalysisTaskServiceTest {

    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private ResumeProfileMapper resumeProfileMapper;
    @Mock
    private ProfileAnalysisMapper profileAnalysisMapper;
    @Mock
    private ProfileAnalyzeTaskProducer producer;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ProfileAnalysisTaskService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // 测试构造函数不注入 SchemaValidator，消息校验逻辑由契约测试另行覆盖。
        service = new ProfileAnalysisTaskService(
                aiTaskMapper,
                resumeProfileMapper,
                profileAnalysisMapper,
                producer,
                new ResumeProperties(),
                objectMapper,
                transactionManager);
    }

    private ResumeProfile profile() {
        return ResumeProfile.builder()
                .id(12L)
                .userId(7L)
                .resumeFileId(3L)
                .version(2)
                .confirmStatus(ConfirmStatus.CONFIRMED.getCode())
                .build();
    }

    private AiTask vectorizeSuccessTask() {
        return AiTask.builder()
                .taskId("v1")
                .taskType(TaskType.RESUME_VECTORIZE.getCode())
                .taskStatus(TaskStatus.SUCCESS.getCode())
                .bizType(BizType.RESUME_PROFILE.getCode())
                .bizId(12L)
                .profileVersion(2)
                .operation("UPSERT")
                .build();
    }

    private AiTask analyzeTask(TaskStatus status, String taskId) {
        return AiTask.builder()
                .taskId(taskId)
                .userId(7L)
                .taskType(TaskType.PROFILE_ANALYZE.getCode())
                .taskStatus(status.getCode())
                .bizType(BizType.RESUME_PROFILE.getCode())
                .bizId(12L)
                .profileVersion(2)
                .traceId("trace")
                .requestPayloadJson("{\"roleDirection\":\"JAVA_BACKEND\"}")
                .retryCount(0)
                .maxRetry(3)
                .build();
    }

    private ProfileAnalyzeResultMessage resultMessage(boolean success) throws Exception {
        ProfileAnalyzeResultMessage.ProfileAnalyzeResultMessageBuilder builder =
                ProfileAnalyzeResultMessage.builder()
                        .taskId("t1")
                        .traceId("trace")
                        .messageType("PROFILE_ANALYZE_RESULT")
                        .schemaVersion("1.0.0")
                        .retryCount(0)
                        .createdAt("2026-08-03T00:00:00Z")
                        .resumeProfileId("12")
                        .profileVersion(2)
                        .roleDirection("JAVA_BACKEND")
                        .success(success);
        if (success) {
            builder.skillTags(objectMapper.readTree(
                    "[{\"skill\":\"Java\",\"level\":\"EXPERT\",\"source\":\"PROJECT\"}]"))
                    .suggestedTopics(objectMapper.readTree("[\"并发\",\"JVM\"]"))
                    .modelName("deepseek-v4-flash")
                    .modelVersion("1.0.0");
        } else {
            builder.errorMessage("LLM 服务暂时不可用");
        }
        return builder.build();
    }

    // ==================== ensureTask ====================

    @Test
    void ensureTask_createsNewTaskWhenVectorizeCompletedAndNoExisting() throws Exception {
        when(resumeProfileMapper.selectOne(any())).thenReturn(profile());
        when(aiTaskMapper.selectOne(any())).thenReturn(vectorizeSuccessTask());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(null);
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());
        when(producer.sendWithRetry(any(), anyInt(), anyLong())).thenReturn(true);

        ProfileAnalysisStatusDto status = service.ensureTask(12L, 7L, "JAVA_BACKEND");

        assertThat(status.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(status.getRoleDirection()).isEqualTo("JAVA_BACKEND");
        verify(aiTaskMapper).insert(any(AiTask.class));
    }

    @Test
    void ensureTask_rejectsWhenVectorizeNotCompleted() {
        when(resumeProfileMapper.selectOne(any())).thenReturn(profile());
        // 不存在向量入库 SUCCESS 任务 → 前置校验失败
        when(aiTaskMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.ensureTask(12L, 7L, "JAVA_BACKEND"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("向量尚未入库");
        verify(aiTaskMapper, never()).insert(any(AiTask.class));
    }

    @Test
    void ensureTask_rejectsUnsupportedDirection() {
        assertThatThrownBy(() -> service.ensureTask(12L, 7L, "PYTHON_BACKEND"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ensureTask_returnsSuccessWhenAnalysisExists() {
        when(resumeProfileMapper.selectOne(any())).thenReturn(profile());
        when(aiTaskMapper.selectOne(any())).thenReturn(vectorizeSuccessTask());
        ProfileAnalysis existing = ProfileAnalysis.builder().id(99L).build();
        when(profileAnalysisMapper.selectOne(any())).thenReturn(existing);
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());

        ProfileAnalysisStatusDto status = service.ensureTask(12L, 7L, "JAVA_BACKEND");

        assertThat(status.getStatus()).isEqualTo(TaskStatus.SUCCESS.getCode());
        assertThat(status.getProfileAnalysisId()).isEqualTo("99");
        verify(aiTaskMapper, never()).insert(any(AiTask.class));
    }

    @Test
    void ensureTask_returnsPendingWhenTaskInProgress() {
        when(resumeProfileMapper.selectOne(any())).thenReturn(profile());
        when(aiTaskMapper.selectOne(any())).thenReturn(vectorizeSuccessTask());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(null);
        AiTask pending = analyzeTask(TaskStatus.PROCESSING, "t-running");
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(pending));

        ProfileAnalysisStatusDto status = service.ensureTask(12L, 7L, "JAVA_BACKEND");

        assertThat(status.getStatus()).isEqualTo(TaskStatus.PROCESSING.getCode());
        assertThat(status.getTaskId()).isEqualTo("t-running");
        verify(aiTaskMapper, never()).insert(any(AiTask.class));
    }

    // ==================== retry ====================

    @Test
    void retry_createsCompensationTaskAfterFailure() {
        when(resumeProfileMapper.selectOne(any())).thenReturn(profile());
        when(aiTaskMapper.selectOne(any())).thenReturn(vectorizeSuccessTask());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(null);
        AiTask failed = analyzeTask(TaskStatus.FAILED, "t-old");
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(failed));
        when(producer.sendWithRetry(any(), anyInt(), anyLong())).thenReturn(true);

        ProfileAnalysisStatusDto status = service.retry(12L, 7L, "JAVA_BACKEND");

        assertThat(status.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        verify(aiTaskMapper).insert(any(AiTask.class));
    }

    // ==================== handleResult ====================

    @Test
    void handleResult_successWritesAnalysisAndMarksTaskSuccess() throws Exception {
        AiTask task = analyzeTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        when(profileAnalysisMapper.selectForUpsert(any(), any(), any())).thenReturn(null);

        service.handleResult(resultMessage(true));

        ArgumentCaptor<ProfileAnalysis> analysisCaptor =
                ArgumentCaptor.forClass(ProfileAnalysis.class);
        verify(profileAnalysisMapper).insert(analysisCaptor.capture());
        ProfileAnalysis saved = analysisCaptor.getValue();
        assertThat(saved.getResumeProfileId()).isEqualTo(12L);
        assertThat(saved.getRoleDirection()).isEqualTo("JAVA_BACKEND");
        assertThat(saved.getProfileVersion()).isEqualTo(2);
        assertThat(saved.getSkillTagsJson()).contains("Java");
        assertThat(saved.getModelName()).isEqualTo("deepseek-v4-flash");

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus())
                .isEqualTo(TaskStatus.SUCCESS.getCode());
    }

    @Test
    void handleResult_failureMarksTaskFailedWithoutAnalysis() throws Exception {
        AiTask task = analyzeTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);

        service.handleResult(resultMessage(false));

        verify(profileAnalysisMapper, never()).insert(any(ProfileAnalysis.class));
        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus())
                .isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("LLM 服务暂时不可用");
    }

    @Test
    void handleResult_ignoresDuplicateTerminalResult() throws Exception {
        AiTask task = analyzeTask(TaskStatus.SUCCESS, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);

        service.handleResult(resultMessage(true));

        // 终态任务重复结果只忽略，不再写分析或更新任务。
        verify(profileAnalysisMapper, never()).insert(any(ProfileAnalysis.class));
        verify(aiTaskMapper, never()).updateById(any(AiTask.class));
    }

    @Test
    void handleResult_updatesExistingAnalysisOnReRun() throws Exception {
        AiTask task = analyzeTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        ProfileAnalysis existing = ProfileAnalysis.builder().id(88L).deleted(0).build();
        when(profileAnalysisMapper.selectForUpsert(any(), any(), any())).thenReturn(existing);

        service.handleResult(resultMessage(true));

        // 已存在同键分析 → 整体替换更新而非插入
        verify(profileAnalysisMapper, never()).insert(any(ProfileAnalysis.class));
        verify(profileAnalysisMapper).update(
                any(),
                any(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class));
    }

    // ==================== getStatus ====================

    @Test
    void getStatus_returnsTaskStatusWhenNoAnalysis() {
        when(resumeProfileMapper.selectOne(any())).thenReturn(profile());
        when(profileAnalysisMapper.selectOne(any())).thenReturn(null);
        AiTask pending = analyzeTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectList(any())).thenReturn(List.of(pending));

        ProfileAnalysisStatusDto status = service.getStatus(12L, 7L, "JAVA_BACKEND");

        assertThat(status.getStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(status.getTaskId()).isEqualTo("t1");
    }

    @Test
    void getStatus_returnsSuccessWhenAnalysisExists() {
        when(resumeProfileMapper.selectOne(any())).thenReturn(profile());
        ProfileAnalysis existing = ProfileAnalysis.builder().id(77L).build();
        when(profileAnalysisMapper.selectOne(any())).thenReturn(existing);
        when(aiTaskMapper.selectList(any())).thenReturn(List.of());

        ProfileAnalysisStatusDto status = service.getStatus(12L, 7L, "JAVA_BACKEND");

        assertThat(status.getStatus()).isEqualTo(TaskStatus.SUCCESS.getCode());
        assertThat(status.getProfileAnalysisId()).isEqualTo("77");
    }
}
