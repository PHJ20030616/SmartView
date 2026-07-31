package com.smartview.resume.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.enums.ConfirmStatus;
import com.smartview.common.enums.ParseStatus;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.exception.BusinessException;
import com.smartview.common.validation.SchemaValidator;
import com.smartview.resume.entity.ResumeFile;
import com.smartview.resume.entity.ResumeProfile;
import com.smartview.resume.dto.UpdateResumeProfileRequest;
import com.smartview.resume.mapper.ResumeFileMapper;
import com.smartview.resume.mapper.ResumeProfileMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ResumeParseResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
class ResumeProfileServiceTest {

    @Mock
    private ResumeProfileMapper resumeProfileMapper;

    @Mock
    private ResumeFileMapper resumeFileMapper;

    @Mock
    private AiTaskMapper aiTaskMapper;

    @Mock
    private SchemaValidator schemaValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResumeProfileService service;

    @BeforeEach
    void setUp() {
        service = new ResumeProfileService(
                resumeProfileMapper, resumeFileMapper, aiTaskMapper, objectMapper, schemaValidator);
    }

    // ==================== 成功场景 ====================

    @Test
    void handleResult_shouldCreateProfileAndUpdateStatusesWhenSuccess() {
        ResumeParseResultMessage message = buildSuccessMessage("task-001", "1");
        AiTask aiTask = buildAiTask("task-001", TaskStatus.PENDING.getCode());
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        resumeFile.setParseTaskId("task-001");
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);
        when(resumeProfileMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.handleResult(message);

        // 验证画像创建
        ArgumentCaptor<ResumeProfile> profileCaptor = ArgumentCaptor.forClass(ResumeProfile.class);
        verify(resumeProfileMapper).insert(profileCaptor.capture());
        ResumeProfile profile = profileCaptor.getValue();
        assertThat(profile.getUserId()).isEqualTo(100L);
        assertThat(profile.getResumeFileId()).isEqualTo(1L);
        assertThat(profile.getCandidateName()).isEqualTo("张三");
        assertThat(profile.getConfirmStatus()).isEqualTo(ConfirmStatus.UNCONFIRMED.getCode());
        assertThat(profile.getVersion()).isEqualTo(1);
        assertThat(profile.getRawText()).isEqualTo("简历原文内容");
        assertThat(profile.getContactInfoJson()).isNotNull();
        assertThat(profile.getEducationJson()).isNotNull();
        assertThat(profile.getSkillsJson()).isNotNull();

        // 验证 ResumeFile 更新为成功
        ArgumentCaptor<ResumeFile> rfCaptor = ArgumentCaptor.forClass(ResumeFile.class);
        verify(resumeFileMapper).updateById(rfCaptor.capture());
        ResumeFile updatedRf = rfCaptor.getValue();
        assertThat(updatedRf.getParseStatus()).isEqualTo(ParseStatus.SUCCESS.getCode());
        assertThat(updatedRf.getErrorMessage()).isNull();

        // 验证 AiTask 更新为成功
        ArgumentCaptor<AiTask> atCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(atCaptor.capture());
        AiTask updatedAt = atCaptor.getValue();
        assertThat(updatedAt.getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.getCode());
        assertThat(updatedAt.getFinishedAt()).isNotNull();
    }

    @Test
    void handleResult_shouldLockTaskRowBeforeProcessingResult() {
        ResumeParseResultMessage message = buildSuccessMessage("task-lock", "1");
        AiTask aiTask = buildAiTask("task-lock", TaskStatus.PENDING.getCode());
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        resumeFile.setParseTaskId("task-lock");
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);
        when(resumeProfileMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.handleResult(message);

        ArgumentCaptor<LambdaQueryWrapper<AiTask>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiTaskMapper, atLeastOnce()).selectOne(wrapperCaptor.capture());
        assertThat(readLastSql(wrapperCaptor.getValue()).trim()).isEqualTo("FOR UPDATE");
    }

    // ==================== 失败场景 ====================

    @Test
    void handleResult_shouldUpdateErrorStatusesWhenFailure() {
        ResumeParseResultMessage message = buildFailureMessage("task-002", "1", "PDF损坏无法解析");
        AiTask aiTask = buildAiTask("task-002", TaskStatus.PENDING.getCode());
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        resumeFile.setParseTaskId("task-002");
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);

        service.handleResult(message);

        // 验证不创建画像
        verify(resumeProfileMapper, never()).insert(any(ResumeProfile.class));

        // 验证 ResumeFile 更新为失败
        ArgumentCaptor<ResumeFile> rfCaptor = ArgumentCaptor.forClass(ResumeFile.class);
        verify(resumeFileMapper).updateById(rfCaptor.capture());
        ResumeFile updatedRf = rfCaptor.getValue();
        assertThat(updatedRf.getParseStatus()).isEqualTo(ParseStatus.FAILED.getCode());
        assertThat(updatedRf.getErrorMessage()).isEqualTo("PDF损坏无法解析");

        // 验证 AiTask 更新为失败
        ArgumentCaptor<AiTask> atCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(atCaptor.capture());
        AiTask updatedAt = atCaptor.getValue();
        assertThat(updatedAt.getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(updatedAt.getErrorMessage()).isEqualTo("PDF损坏无法解析");
    }

    // ==================== 幂等性场景 ====================

    @Test
    void handleResult_shouldMarkRetryableFailureAsRetrying() {
        ResumeParseResultMessage message = buildFailureMessage("task-002-retry", "1", "AI 暂时不可用");
        AiTask aiTask = buildAiTask("task-002-retry", TaskStatus.PENDING.getCode());
        aiTask.setRetryCount(0);
        aiTask.setMaxRetry(3);
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        resumeFile.setParseTaskId("task-002-retry");
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);

        service.handleResult(message);

        assertThat(aiTask.getTaskStatus()).isEqualTo(TaskStatus.RETRYING.getCode());
        assertThat(aiTask.getFinishedAt()).isNull();
        assertThat(resumeFile.getParseStatus()).isEqualTo(ParseStatus.PENDING.getCode());
        assertThat(resumeFile.getErrorMessage()).isNull();
    }

    @Test
    void handleResult_shouldFinalizeWhenWorkerAlreadyExhaustedRetries() {
        ResumeParseResultMessage message = buildFailureMessage(
                "task-002-worker-exhausted", "1", "AI 服务持续不可用");
        message.setRetryCount(3);
        AiTask aiTask = buildAiTask("task-002-worker-exhausted", TaskStatus.PENDING.getCode());
        aiTask.setRetryCount(0);
        aiTask.setMaxRetry(3);
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        resumeFile.setParseTaskId("task-002-worker-exhausted");
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);

        service.handleResult(message);

        // worker 已用尽重试预算时，Spring 必须立即结束任务，不能重新进入调度轮询。
        assertThat(aiTask.getRetryCount()).isEqualTo(3);
        assertThat(aiTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(aiTask.getFinishedAt()).isNotNull();
        assertThat(resumeFile.getParseStatus()).isEqualTo(ParseStatus.FAILED.getCode());
        assertThat(resumeFile.getErrorMessage()).isEqualTo("AI 服务持续不可用");
    }

    @Test
    void handleResult_shouldSkipWhenTaskAlreadySuccess() {
        ResumeParseResultMessage message = buildSuccessMessage("task-003", "1");
        AiTask aiTask = buildAiTask("task-003", TaskStatus.SUCCESS.getCode());
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);

        service.handleResult(message);

        // 不应调用任何更新操作
        verify(resumeProfileMapper, never()).insert(any(ResumeProfile.class));
        verify(resumeFileMapper, never()).updateById(any(ResumeFile.class));
        verify(aiTaskMapper, never()).updateById(any(AiTask.class));
    }

    @Test
    void handleResult_shouldSkipWhenTaskAlreadyFailed() {
        ResumeParseResultMessage message = buildFailureMessage("task-004", "1", "解析失败");
        AiTask aiTask = buildAiTask("task-004", TaskStatus.FAILED.getCode());
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);

        service.handleResult(message);

        verify(resumeProfileMapper, never()).insert(any(ResumeProfile.class));
        verify(resumeFileMapper, never()).updateById(any(ResumeFile.class));
        verify(aiTaskMapper, never()).updateById(any(AiTask.class));
    }

    // ==================== 消息校验场景 ====================

    @Test
    void handleResult_shouldThrowWhenTaskIdMissing() {
        ResumeParseResultMessage message = buildSuccessMessage(null, "1");

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void handleResult_shouldThrowWhenResumeFileIdMissing() {
        ResumeParseResultMessage message = buildSuccessMessage("task-005", null);

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resumeFileId");
    }

    @Test
    void handleResult_shouldThrowWhenSuccessMissingRawText() {
        ResumeParseResultMessage message = buildSuccessMessage("task-006", "1");
        message.setRawText(null);

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rawText");
    }

    @Test
    void handleResult_shouldThrowWhenFailureMissingErrorMessage() {
        ResumeParseResultMessage message = buildFailureMessage("task-007", "1", null);

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("errorMessage");
    }

    // ==================== 边界场景 ====================

    @Test
    void handleResult_shouldUpdateTaskFailedWhenResumeFileNotFound() {
        ResumeParseResultMessage message = buildSuccessMessage("task-008", "999");
        AiTask aiTask = buildAiTask("task-008", TaskStatus.PENDING.getCode());
        aiTask.setBizId(999L); // bizId 与消息的 resumeFileId=999 一致，但文件不存在
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        // 校验通过的 bizId=999 指向不存在的文件
        when(resumeFileMapper.selectById(999L)).thenReturn(null);

        service.handleResult(message);

        // 不应创建画像
        verify(resumeProfileMapper, never()).insert(any(ResumeProfile.class));
        // 应更新 AiTask 为失败
        ArgumentCaptor<AiTask> atCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(atCaptor.capture());
        AiTask updatedAt = atCaptor.getValue();
        assertThat(updatedAt.getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(updatedAt.getErrorMessage()).contains("简历文件不存在");
    }

    @Test
    void markResultHandlingFailed_shouldFinalizeTaskAndResumeFileInNewTransaction() {
        ResumeParseResultMessage message = buildFailureMessage("task-dlq", "1", "消息字段不匹配");
        AiTask aiTask = buildAiTask("task-dlq", TaskStatus.PROCESSING.getCode());
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);
        when(aiTaskMapper.update(isNull(), any())).thenReturn(1);
        when(resumeFileMapper.update(isNull(), any())).thenReturn(1);

        service.markResultHandlingFailed(message, "消息字段不匹配");

        assertThat(aiTask.getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(aiTask.getErrorMessage()).isEqualTo("消息字段不匹配");
        assertThat(aiTask.getFinishedAt()).isNotNull();
        assertThat(resumeFile.getParseStatus()).isEqualTo(ParseStatus.FAILED.getCode());
        assertThat(resumeFile.getErrorMessage()).isEqualTo("消息字段不匹配");
        verify(aiTaskMapper).update(isNull(), any());
        verify(resumeFileMapper).update(isNull(), any());
    }

    @Test
    void markResultHandlingFailed_shouldNotUpdateFileWhenTaskSucceededConcurrently() {
        ResumeParseResultMessage message = buildFailureMessage("task-dlq-race", "1", "消息字段不匹配");
        AiTask aiTask = buildAiTask("task-dlq-race", TaskStatus.PROCESSING.getCode());
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(aiTaskMapper.update(isNull(), any())).thenAnswer(invocation -> {
            // 模拟条件更新执行前，合法消费者已经将任务完成。
            aiTask.setTaskStatus(TaskStatus.SUCCESS.getCode());
            return 0;
        });

        service.markResultHandlingFailed(message, "消息字段不匹配");

        verify(resumeFileMapper, never()).selectById(1L);
        verify(resumeFileMapper, never()).update(isNull(), any());
    }

    @Test
    void handleResult_shouldIncrementVersionForMultipleProfiles() {
        ResumeParseResultMessage message = buildSuccessMessage("task-010", "1");
        AiTask aiTask = buildAiTask("task-010", TaskStatus.PENDING.getCode());
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        resumeFile.setParseTaskId("task-010");
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);
        when(resumeProfileMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        service.handleResult(message);

        ArgumentCaptor<ResumeProfile> profileCaptor = ArgumentCaptor.forClass(ResumeProfile.class);
        verify(resumeProfileMapper).insert(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getVersion()).isEqualTo(3);
    }

    @Test
    void handleResult_shouldThrowWhenResumeFileIdNotNumeric() {
        ResumeParseResultMessage message = buildSuccessMessage("task-011", "not-a-number");

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("resumeFileId");
    }

    @Test
    void handleResult_shouldThrowWhenAiTaskNotFound() {
        ResumeParseResultMessage message = buildSuccessMessage("task-012", "1");
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 任务不存在");
    }

    // ==================== 交叉校验场景 ====================

    @Test
    void handleResult_shouldThrowWhenBizIdMismatch() {
        ResumeParseResultMessage message = buildSuccessMessage("task-013", "1");
        AiTask aiTask = buildAiTask("task-013", TaskStatus.PENDING.getCode());
        aiTask.setBizId(999L); // bizId 与 resumeFileId=1 不一致
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);

        // validateAiTaskBizRelation 在校验 bizId 后直接抛异常，
        // 不会执行到 selectById，无需对其 stub
        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void handleResult_shouldThrowWhenParseTaskIdMismatch() {
        ResumeParseResultMessage message = buildSuccessMessage("task-014", "1");
        AiTask aiTask = buildAiTask("task-014", TaskStatus.PENDING.getCode());
        ResumeFile resumeFile = buildResumeFile(1L, 100L);
        resumeFile.setParseTaskId("some-other-task"); // parseTaskId 与 taskId=task-014 不一致
        when(aiTaskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(aiTask);
        when(resumeFileMapper.selectById(1L)).thenReturn(resumeFile);

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void updateProfile_shouldMergeContactFieldsWithoutDroppingExistingValues() throws Exception {
        ResumeProfile profile = ResumeProfile.builder()
                .id(7L)
                .userId(100L)
                .resumeFileId(1L)
                .candidateName("张三")
                .contactInfoJson("{\"phone\":\"旧手机号\",\"location\":\"上海\"}")
                .confirmStatus(ConfirmStatus.UNCONFIRMED.getCode())
                .skillsJson("[\"Java\"]")
                .build();
        UpdateResumeProfileRequest request = UpdateResumeProfileRequest.builder()
                .contactInfo(Map.of(
                        "phone", "新手机号",
                        "email", "zhangsan@example.com"))
                .build();
        when(resumeProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(profile);

        service.updateProfile(7L, 100L, request);

        ArgumentCaptor<ResumeProfile> profileCaptor = ArgumentCaptor.forClass(ResumeProfile.class);
        verify(resumeProfileMapper).updateById(profileCaptor.capture());
        Map<?, ?> contactInfo = objectMapper.readValue(
                profileCaptor.getValue().getContactInfoJson(), Map.class);
        assertThat(contactInfo.get("phone")).isEqualTo("新手机号");
        assertThat(contactInfo.get("email")).isEqualTo("zhangsan@example.com");
        assertThat(contactInfo.get("location")).isEqualTo("上海");
    }

    @Test
    void confirmProfile_shouldLockProfileRowBeforeUpdating() {
        ResumeProfile profile = ResumeProfile.builder()
                .id(8L)
                .userId(100L)
                .resumeFileId(1L)
                .candidateName("张三")
                .confirmStatus(ConfirmStatus.UNCONFIRMED.getCode())
                .build();
        when(resumeProfileMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(profile);

        service.confirmProfile(8L, 100L);

        ArgumentCaptor<LambdaQueryWrapper<ResumeProfile>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(resumeProfileMapper).selectOne(wrapperCaptor.capture());
        assertThat(readLastSql(wrapperCaptor.getValue()).trim()).isEqualTo("FOR UPDATE");
        assertThat(profile.getConfirmStatus()).isEqualTo(ConfirmStatus.CONFIRMED.getCode());
        verify(resumeProfileMapper).updateById(profile);
    }

    // ==================== 辅助方法 ====================

    private ResumeParseResultMessage buildSuccessMessage(String taskId, String resumeFileId) {
        return ResumeParseResultMessage.builder()
                .taskId(taskId)
                .traceId("trace-001")
                .messageType("RESUME_PARSE_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                .createdAt(LocalDateTime.now().toString())
                .resumeFileId(resumeFileId)
                .success(true)
                .candidateName("张三")
                .contactInfo(Map.of("phone", "13800138000", "email", "zhangsan@example.com"))
                .education(List.of(Map.of("school", "清华大学", "degree", "本科")))
                .workExperience(List.of(Map.of("company", "字节跳动", "position", "Java开发")))
                .projectExperience(List.of(Map.of("projectName", "电商平台", "role", "后端开发")))
                .skills(List.of("Java", "Spring Boot", "MySQL"))
                .rawText("简历原文内容")
                .build();
    }

    private ResumeParseResultMessage buildFailureMessage(String taskId, String resumeFileId, String errorMessage) {
        return ResumeParseResultMessage.builder()
                .taskId(taskId)
                .traceId("trace-001")
                .messageType("RESUME_PARSE_RESULT")
                .schemaVersion("1.0.0")
                .retryCount(0)
                .createdAt(LocalDateTime.now().toString())
                .resumeFileId(resumeFileId)
                .success(false)
                .errorMessage(errorMessage)
                .build();
    }

    private AiTask buildAiTask(String taskId, String taskStatus) {
        AiTask aiTask = new AiTask();
        aiTask.setId(1L);
        aiTask.setTaskId(taskId);
        aiTask.setTaskStatus(taskStatus);
        aiTask.setUserId(100L);
        aiTask.setBizType("RESUME_FILE");
        aiTask.setBizId(1L); // 默认匹配 resumeFileId=1
        return aiTask;
    }

    private ResumeFile buildResumeFile(Long id, Long userId) {
        return ResumeFile.builder()
                .id(id)
                .userId(userId)
                .originalFilename("test.pdf")
                .objectKey("resumes/test.pdf")
                .parseStatus(ParseStatus.PROCESSING.getCode())
                .build();
    }

    private String readLastSql(AbstractWrapper<?, ?, ?> wrapper) {
        try {
            /*
             * MyBatis-Plus 未提供公开的 last SQL 读取方法，测试通过反射确认行锁片段没有被遗漏。
             * 该断言只验证构造出的 wrapper，不参与业务运行时逻辑。
             */
            Field field = AbstractWrapper.class.getDeclaredField("lastSql");
            field.setAccessible(true);
            return ((SharedString) field.get(wrapper)).getStringValue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法读取 MyBatis-Plus wrapper 的 last SQL", e);
        }
    }
}
