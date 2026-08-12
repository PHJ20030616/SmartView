package com.smartview.report.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.enums.InterviewSessionStatus;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.report.entity.InterviewReport;
import com.smartview.report.entity.ReferenceAnswer;
import com.smartview.report.enums.ReportStatus;
import com.smartview.report.mapper.InterviewReportMapper;
import com.smartview.report.mapper.ReferenceAnswerMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ReportGenerateResultMessage;
import com.smartview.task.mq.ReportTaskProducer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告生成任务服务测试。
 *
 * 覆盖：幂等建任务（报告已存在/任务进行中）、结果成功落库（报告内容+参考答案+会话推进）、
 * 结果失败仅标记报告 FAILED 与任务 FAILED、终态重复结果忽略、结果与任务关联校验。
 */
@ExtendWith(MockitoExtension.class)
class ReportTaskServiceTest {

    @Mock
    private AiTaskMapper aiTaskMapper;
    @Mock
    private InterviewReportMapper interviewReportMapper;
    @Mock
    private ReferenceAnswerMapper referenceAnswerMapper;
    @Mock
    private InterviewSessionMapper sessionMapper;
    @Mock
    private ReportTaskProducer producer;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ReportTaskService service;
    private ObjectMapper objectMapper;

    /**
     * 初始化涉及 Lambda 包装器的实体表元数据（MyBatis-Plus 缓存）。
     *
     * 纯 Mockito 单测没有 MyBatis-Plus 启动流程，而 LambdaUpdateWrapper.set() 与
     * LambdaQueryWrapper.in() 的列名解析是急切的，依赖 TableInfoHelper 构建的缓存；
     * 沿用 InterviewSessionServiceTest 既有约定手动初始化，否则这些调用抛
     * "can not find lambda cache for this entity"。
     */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                AiTask.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                InterviewSession.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                InterviewReport.class);
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // 测试构造函数不注入 SchemaValidator，消息校验逻辑由契约测试另行覆盖。
        service = new ReportTaskService(
                aiTaskMapper,
                interviewReportMapper,
                referenceAnswerMapper,
                sessionMapper,
                producer,
                new ResumeProperties(),
                objectMapper,
                transactionManager);
    }

    private InterviewSession session() {
        return InterviewSession.builder()
                .id(88L)
                .userId(7L)
                .resumeProfileId(12L)
                .status(InterviewSessionStatus.REPORTING.getCode())
                .build();
    }

    private AiTask reportTask(TaskStatus status, String taskId) {
        return AiTask.builder()
                .taskId(taskId)
                .userId(7L)
                .taskType(TaskType.REPORT_GENERATE.getCode())
                .taskStatus(status.getCode())
                .bizType(BizType.INTERVIEW_SESSION.getCode())
                .bizId(88L)
                .traceId("trace")
                .retryCount(0)
                .maxRetry(3)
                .build();
    }

    private ReportGenerateResultMessage resultMessage(boolean success) throws Exception {
        ReportGenerateResultMessage.ReportGenerateResultMessageBuilder builder =
                ReportGenerateResultMessage.builder()
                        .taskId("t1")
                        .traceId("trace")
                        .messageType("REPORT_GENERATE_RESULT")
                        .schemaVersion("1.0.0")
                        .retryCount(0)
                        .createdAt("2026-08-12T00:00:00Z")
                        .sessionId("88")
                        .success(success)
                        .reportId("5");
        if (success) {
            builder.overallScore(72)
                    .readinessLevel("READY")
                    .roleFitScore(80)
                    .summary("总体评价")
                    .strengths(objectMapper.readTree("[\"基础扎实\"]"))
                    .weaknesses(objectMapper.readTree("[\"深度不足\"]"))
                    .riskPoints(objectMapper.readTree("[\"项目描述空泛\"]"))
                    .suggestions(objectMapper.readTree(
                            "[{\"topic\":\"并发\",\"reason\":\"薄弱\",\"resources\":[]}]"))
                    .coverage(objectMapper.readTree(
                            "{\"basicCoverage\":0.8,\"projectCoverage\":1.0,\"scenarioCoverage\":0.5}"))
                    .referenceAnswers(objectMapper.readTree(
                            "[{\"questionId\":\"10\",\"answerType\":\"BASIC_KEY_POINTS\","
                                    + "\"referenceContent\":\"参考答案\",\"keyPoints\":[\"要点\"],\"tradeoffs\":[]}]"));
        } else {
            builder.errorMessage("LLM 服务暂时不可用");
        }
        return builder.build();
    }

    // ==================== startReportGeneration ====================

    @Test
    void startReportGeneration_createsReportAndTaskAndPublishes() {
        when(interviewReportMapper.selectOne(any())).thenReturn(null);
        when(aiTaskMapper.selectOne(any())).thenReturn(null);
        when(producer.sendWithRetry(any(), anyInt(), anyLong())).thenReturn(true);

        service.startReportGeneration(session());

        ArgumentCaptor<InterviewReport> reportCaptor =
                ArgumentCaptor.forClass(InterviewReport.class);
        verify(interviewReportMapper).insert(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getStatus())
                .isEqualTo(ReportStatus.GENERATING.getCode());
        assertThat(reportCaptor.getValue().getSessionId()).isEqualTo(88L);
        assertThat(reportCaptor.getValue().getUserId()).isEqualTo(7L);

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskType())
                .isEqualTo(TaskType.REPORT_GENERATE.getCode());
        assertThat(taskCaptor.getValue().getTaskStatus())
                .isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(taskCaptor.getValue().getBizId()).isEqualTo(88L);

        // 无活动事务时 publish 立即执行 → producer 被调用
        verify(producer).sendWithRetry(any(), anyInt(), anyLong());
    }

    @Test
    void startReportGeneration_skipsWhenReportExists() {
        InterviewReport existing = InterviewReport.builder().id(5L).build();
        when(interviewReportMapper.selectOne(any())).thenReturn(existing);

        service.startReportGeneration(session());

        verify(aiTaskMapper, never()).insert(any(AiTask.class));
        verify(producer, never()).sendWithRetry(any(), anyInt(), anyLong());
    }

    @Test
    void startReportGeneration_skipsWhenTaskInProgress() {
        when(interviewReportMapper.selectOne(any())).thenReturn(null);
        when(aiTaskMapper.selectOne(any())).thenReturn(reportTask(TaskStatus.PROCESSING, "t-running"));

        service.startReportGeneration(session());

        verify(aiTaskMapper, never()).insert(any(AiTask.class));
    }

    // ==================== handleResult ====================

    @Test
    void handleResult_successUpdatesReportAndInsertsReferenceAnswers() throws Exception {
        AiTask task = reportTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        when(interviewReportMapper.selectOne(any())).thenReturn(
                InterviewReport.builder().id(5L).sessionId(88L).build());

        service.handleResult(resultMessage(true));

        ArgumentCaptor<InterviewReport> reportCaptor =
                ArgumentCaptor.forClass(InterviewReport.class);
        verify(interviewReportMapper).updateById(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getStatus()).isEqualTo(ReportStatus.SUCCESS.getCode());
        assertThat(reportCaptor.getValue().getOverallScore()).isEqualTo(72);
        assertThat(reportCaptor.getValue().getReadinessLevel()).isEqualTo("READY");

        ArgumentCaptor<ReferenceAnswer> answerCaptor =
                ArgumentCaptor.forClass(ReferenceAnswer.class);
        verify(referenceAnswerMapper).insert(answerCaptor.capture());
        assertThat(answerCaptor.getValue().getReportId()).isEqualTo(5L);
        assertThat(answerCaptor.getValue().getQuestionId()).isEqualTo(10L);
        assertThat(answerCaptor.getValue().getAnswerType())
                .isEqualTo("BASIC_KEY_POINTS");

        // 会话 REPORTING→COMPLETED（条件更新）
        // 注意：completeSession 使用 LambdaUpdateWrapper，与 UpdateWrapper 为兄弟类，
        // 必须按 LambdaUpdateWrapper 匹配（简报原写法 any(UpdateWrapper.class) 永不匹配）。
        verify(sessionMapper).update(any(), any(LambdaUpdateWrapper.class));

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.SUCCESS.getCode());
    }

    @Test
    void handleResult_failureMarksReportAndTaskFailed() throws Exception {
        AiTask task = reportTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        when(interviewReportMapper.selectOne(any())).thenReturn(
                InterviewReport.builder().id(5L).sessionId(88L).build());

        service.handleResult(resultMessage(false));

        ArgumentCaptor<InterviewReport> reportCaptor =
                ArgumentCaptor.forClass(InterviewReport.class);
        verify(interviewReportMapper).updateById(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getStatus()).isEqualTo(ReportStatus.FAILED.getCode());
        verify(referenceAnswerMapper, never()).insert(any(ReferenceAnswer.class));

        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.FAILED.getCode());
        assertThat(taskCaptor.getValue().getErrorMessage()).isEqualTo("LLM 服务暂时不可用");
    }

    @Test
    void handleResult_ignoresDuplicateTerminalResult() throws Exception {
        AiTask task = reportTask(TaskStatus.SUCCESS, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);

        service.handleResult(resultMessage(true));

        verify(interviewReportMapper, never()).updateById(any(InterviewReport.class));
        verify(referenceAnswerMapper, never()).insert(any(ReferenceAnswer.class));
        verify(aiTaskMapper, never()).updateById(any(AiTask.class));
    }

    @Test
    void handleResult_rejectsMismatchedTaskRelation() throws Exception {
        AiTask task = reportTask(TaskStatus.PENDING, "t1");
        // 任务 bizId=88 与消息 sessionId=99 不匹配
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        ReportGenerateResultMessage message = resultMessage(true);
        message.setSessionId("99");

        assertThatThrownBy(() -> service.handleResult(message))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void handleResult_rejectsMissingReport() throws Exception {
        AiTask task = reportTask(TaskStatus.PENDING, "t1");
        when(aiTaskMapper.selectOne(any())).thenReturn(task);
        when(interviewReportMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.handleResult(resultMessage(true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("面试报告不存在");
    }

    // ==================== compensateReportTask ====================

    @Test
    void compensateReportTask_retiresOldTaskAndCreatesNewTaskWhenSessionReporting() {
        AiTask oldTask = reportTask(TaskStatus.FAILED, "t-old");
        when(sessionMapper.selectById(88L)).thenReturn(session());
        when(aiTaskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(producer.sendWithRetry(any(), anyInt(), anyLong())).thenReturn(true);

        boolean compensated = service.compensateReportTask(oldTask);

        assertThat(compensated).isTrue();
        // 租约抢占退休旧任务：条件更新必须绑定观测到的 task_status 并带 finished_at IS NULL
        // 谓词，否则退休后的 FAILED 任务仍会被扫描选中，导致重复补偿与无界累积。
        ArgumentCaptor<UpdateWrapper<AiTask>> updateCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(aiTaskMapper).update(isNull(), updateCaptor.capture());
        assertThat(updateCaptor.getValue().getSqlSegment())
                .contains("task_status")
                .contains("finished_at");
        // 退休成功 → 插入新 taskId 补偿任务 → 事务提交后重投 MQ。
        ArgumentCaptor<AiTask> taskCaptor = ArgumentCaptor.forClass(AiTask.class);
        verify(aiTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskType())
                .isEqualTo(TaskType.REPORT_GENERATE.getCode());
        assertThat(taskCaptor.getValue().getTaskStatus()).isEqualTo(TaskStatus.PENDING.getCode());
        assertThat(taskCaptor.getValue().getBizId()).isEqualTo(88L);
        assertThat(taskCaptor.getValue().getTaskId()).isNotEqualTo("t-old");
        verify(producer).sendWithRetry(any(), anyInt(), anyLong());
    }

    @Test
    void compensateReportTask_skipsWhenSessionNotReporting() {
        AiTask oldTask = reportTask(TaskStatus.FAILED, "t-old");
        InterviewSession completed = session();
        completed.setStatus(InterviewSessionStatus.COMPLETED.getCode());
        when(sessionMapper.selectById(88L)).thenReturn(completed);

        boolean compensated = service.compensateReportTask(oldTask);

        assertThat(compensated).isFalse();
        verify(aiTaskMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(aiTaskMapper, never()).insert(any(AiTask.class));
        verify(producer, never()).sendWithRetry(any(), anyInt(), anyLong());
    }

    @Test
    void compensateReportTask_skipsWhenOldTaskAlreadySucceededOrTakenOver() {
        AiTask oldTask = reportTask(TaskStatus.SUCCESS, "t-old");
        when(sessionMapper.selectById(88L)).thenReturn(session());
        // 条件更新退休影响 0 行（task_status 已 SUCCESS 或已被其他实例抢占）→ 不再重建。
        when(aiTaskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        boolean compensated = service.compensateReportTask(oldTask);

        assertThat(compensated).isFalse();
        verify(aiTaskMapper, never()).insert(any(AiTask.class));
        verify(producer, never()).sendWithRetry(any(), anyInt(), anyLong());
    }

    @Test
    void compensateReportTask_returnsFalseWhenLeasePredicateNotMatched() {
        // 模拟同一任务已被上一轮补偿退休（finished_at 已写入）：本轮旧任务不再满足
        // finished_at IS NULL 租约，条件更新影响 0 行 → 返回 false 且不重建新任务，
        // 避免同一 FAILED 任务在卡死场景下被反复补偿造成任务与 MQ 消息无界累积。
        AiTask oldTask = reportTask(TaskStatus.FAILED, "t-old");
        when(sessionMapper.selectById(88L)).thenReturn(session());
        when(aiTaskMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        boolean compensated = service.compensateReportTask(oldTask);

        assertThat(compensated).isFalse();
        verify(aiTaskMapper, never()).insert(any(AiTask.class));
        verify(producer, never()).sendWithRetry(any(), anyInt(), anyLong());
    }
}
