package com.smartview.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.enums.BizType;
import com.smartview.common.enums.TaskStatus;
import com.smartview.common.enums.TaskType;
import com.smartview.common.exception.BusinessException;
import com.smartview.common.validation.SchemaValidator;
import com.smartview.config.properties.ResumeProperties;
import com.smartview.interview.entity.InterviewSession;
import com.smartview.interview.enums.InterviewSessionStatus;
import com.smartview.interview.mapper.InterviewSessionMapper;
import com.smartview.report.entity.InterviewReport;
import com.smartview.report.entity.ReferenceAnswer;
import com.smartview.report.enums.ReferenceAnswerType;
import com.smartview.report.enums.ReportStatus;
import com.smartview.report.mapper.InterviewReportMapper;
import com.smartview.report.mapper.ReferenceAnswerMapper;
import com.smartview.task.entity.AiTask;
import com.smartview.task.mapper.AiTaskMapper;
import com.smartview.task.mq.ReportGenerateMessage;
import com.smartview.task.mq.ReportGenerateResultMessage;
import com.smartview.task.mq.ReportTaskProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 报告生成任务服务。
 *
 * 关键一致性规则：
 * 1. 会话结束（REPORTING）事务内幂等创建 interview_report(GENERATING) + ai_task(PENDING)，
 *    MQ 只在事务提交后发送，保证 FastAPI 读到的报告行已提交；
 * 2. 报告行唯一索引 (session_id, deleted) 兜底防并发重复，重复投递被唯一约束拒绝；
 * 3. handleResult 在任务行锁保护下更新报告与参考答案，终态任务重复结果只忽略；
 * 4. 报告失败不把已结束的面试改回非终态：无论成功失败，会话一律 REPORTING→COMPLETED，
 *    失败原因记录在 ai_task.errorMessage，报告自身只标记 FAILED；
 * 5. 结果成功时每道 ANSWERED 题写入 reference_answer，唯一索引 (report_id, question_id, deleted)
 *    兜底防同题多份参考答案。
 *
 * @author SmartView Team
 * @since 2026-08-12
 */
@Slf4j
@Service
public class ReportTaskService {

    private static final String MESSAGE_TYPE = "REPORT_GENERATE_TASK";
    private static final String RESULT_MESSAGE_TYPE = "REPORT_GENERATE_RESULT";
    private static final String SCHEMA_VERSION = "1.0.0";

    private final AiTaskMapper aiTaskMapper;
    private final InterviewReportMapper interviewReportMapper;
    private final ReferenceAnswerMapper referenceAnswerMapper;
    private final InterviewSessionMapper sessionMapper;
    private final ReportTaskProducer producer;
    private final ResumeProperties resumeProperties;
    private final ObjectMapper objectMapper;
    private final SchemaValidator schemaValidator;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public ReportTaskService(
            AiTaskMapper aiTaskMapper,
            InterviewReportMapper interviewReportMapper,
            ReferenceAnswerMapper referenceAnswerMapper,
            InterviewSessionMapper sessionMapper,
            ReportTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            SchemaValidator schemaValidator,
            PlatformTransactionManager transactionManager) {
        this.aiTaskMapper = aiTaskMapper;
        this.interviewReportMapper = interviewReportMapper;
        this.referenceAnswerMapper = referenceAnswerMapper;
        this.sessionMapper = sessionMapper;
        this.producer = producer;
        this.resumeProperties = resumeProperties;
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // 必须在 afterCommit 回调中也能独立提交：默认 REQUIRED 会加入已提交的"幻影事务"，
        // isNewTransaction=false 导致 UPDATE 不落库被回滚（详情见 markDispatchFailed javadoc）。
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 兼容既有测试或内部调用方；正式 Bean 使用包含 SchemaValidator 和事务管理器的构造函数。
     */
    public ReportTaskService(
            AiTaskMapper aiTaskMapper,
            InterviewReportMapper interviewReportMapper,
            ReferenceAnswerMapper referenceAnswerMapper,
            InterviewSessionMapper sessionMapper,
            ReportTaskProducer producer,
            ResumeProperties resumeProperties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this(
                aiTaskMapper,
                interviewReportMapper,
                referenceAnswerMapper,
                sessionMapper,
                producer,
                resumeProperties,
                objectMapper,
                null,
                transactionManager);
    }

    /**
     * 会话结束（置 REPORTING）后触发报告生成（幂等）。
     *
     * 在会话结束事务内调用：报告行与 ai_task 随会话推进原子提交，
     * MQ 在事务提交后发送。已存在有效报告行或进行中任务时直接跳过。
     *
     * @param session 已置 REPORTING 的会话实体（只需 id/userId/resumeProfileId）
     */
    @Transactional(rollbackFor = Exception.class)
    public void startReportGeneration(InterviewSession session) {
        Long sessionId = session.getId();

        // 幂等①：已存在有效报告行（GENERATING/SUCCESS/FAILED 均可复用）则不再创建新报告。
        InterviewReport existing = interviewReportMapper.selectOne(
                new LambdaQueryWrapper<InterviewReport>()
                        .eq(InterviewReport::getSessionId, sessionId));
        if (existing != null) {
            log.info("报告行已存在，跳过报告任务创建，sessionId={}, reportId={}",
                    sessionId, existing.getId());
            return;
        }

        // 幂等②：已存在进行中的报告任务则跳过，避免并发触发产生重复 MQ 消息。
        AiTask running = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskType, TaskType.REPORT_GENERATE.getCode())
                        .eq(AiTask::getBizType, BizType.INTERVIEW_SESSION.getCode())
                        .eq(AiTask::getBizId, sessionId)
                        .in(AiTask::getTaskStatus,
                                TaskStatus.PENDING.getCode(),
                                TaskStatus.PROCESSING.getCode(),
                                TaskStatus.RETRYING.getCode())
                        .last("LIMIT 1"));
        if (running != null) {
            log.info("报告生成任务已在进行，跳过，sessionId={}, taskId={}",
                    sessionId, running.getTaskId());
            return;
        }

        InterviewReport report = InterviewReport.builder()
                .sessionId(sessionId)
                .userId(session.getUserId())
                .resumeProfileId(session.getResumeProfileId())
                .status(ReportStatus.GENERATING.getCode())
                .build();
        try {
            interviewReportMapper.insert(report);
        } catch (DuplicateKeyException duplicate) {
            // 并发触发：另一事务已插入报告行，唯一索引兜底，幂等返回。
            log.info("报告行并发重复插入，跳过，sessionId={}", sessionId);
            return;
        }

        AiTask task = buildTask(session);
        aiTaskMapper.insert(task);
        schedulePublishAfterCommit(task, sessionId);
    }

    /**
     * 消费 FastAPI 的报告生成结果。
     *
     * 成功：更新报告内容 + SUCCESS + generatedAt，批量插入参考答案，会话 COMPLETED；
     * 失败：报告 FAILED，会话 COMPLETED，错误记录在 ai_task。终态任务重复结果只忽略。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleResult(ReportGenerateResultMessage message) {
        validateResult(message);
        AiTask task = aiTaskMapper.selectOne(
                new LambdaQueryWrapper<AiTask>()
                        .eq(AiTask::getTaskId, message.getTaskId())
                        // 结果消费需要和同一 taskId 的重复消息串行化。
                        .last("FOR UPDATE"));
        if (task == null) {
            throw new BusinessException("报告生成任务不存在");
        }
        validateTaskRelation(task, message);

        if (TaskStatus.SUCCESS.getCode().equals(task.getTaskStatus())
                || TaskStatus.FAILED.getCode().equals(task.getTaskStatus())) {
            // 终态任务的重复结果只记录并忽略，避免迟到消息覆盖审计数据。
            log.info("报告生成任务已终态，忽略重复结果，taskId={}, status={}",
                    task.getTaskId(), task.getTaskStatus());
            return;
        }

        boolean success = Boolean.TRUE.equals(message.getSuccess());
        InterviewReport report = findReport(message.getSessionId());
        if (report == null) {
            throw new BusinessException("面试报告不存在，sessionId=" + message.getSessionId());
        }

        if (success) {
            applyReportContent(report, message);
        } else {
            report.setStatus(ReportStatus.FAILED.getCode());
            report.setGeneratedAt(null);
            interviewReportMapper.updateById(report);
        }

        // 无论成功失败，面试已结束：会话统一推进为 COMPLETED（报告状态独立表达成败）。
        completeSession(message.getSessionId());

        task.setTaskStatus(success ? TaskStatus.SUCCESS.getCode() : TaskStatus.FAILED.getCode());
        task.setRetryCount(message.getRetryCount());
        task.setErrorMessage(success ? null : message.getErrorMessage());
        task.setFinishedAt(LocalDateTime.now());
        task.setResultPayloadJson(toJson(message));
        aiTaskMapper.updateById(task);
    }

    /**
     * MQ 投递失败时将任务收口为 FAILED，报告与会话状态保持不变。
     *
     * 与画像分析一致不设独立补偿调度器；会话已进入 REPORTING，报告行保留 GENERATING，
     * 由后续人工/运维按 DLQ 与 ai_task 记录恢复。
     *
     * 本方法常在 schedulePublishAfterCommit 的 afterCommit 回调中执行：此时外层事务已
     * doCommit 但尚未 cleanupAfterCompletion，isSynchronizationActive() 仍为 true、连接仍
     * 绑定在 ThreadLocal 且 isTransactionActive() 仍为 true，REQUIRED 会把当前模板加入这个
     * 已提交的"幻影事务"，isNewTransaction=false 导致模板 commit() 不执行 doCommit，UPDATE
     * 最终随连接清理被回滚而静默丢失。因此事务模板强制 REQUIRES_NEW，确保在 afterCommit
     * 回调中独立开启并提交新事务，FAILED 更新真正落库。
     */
    public void markDispatchFailed(String taskId, String errorMessage) {
        if (transactionTemplate == null) {
            log.warn("当前报告任务服务未配置事务管理器，无法补偿 MQ 投递失败，taskId={}", taskId);
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
            task.setTaskStatus(TaskStatus.FAILED.getCode());
            task.setErrorMessage(errorMessage);
            task.setFinishedAt(LocalDateTime.now());
            aiTaskMapper.updateById(task);
        });
    }

    /**
     * 补偿调度重建报告生成任务：退休旧任务并创建新 taskId 补偿任务。
     *
     * 仅当会话仍处于 REPORTING 时执行（报告已成功/失败、会话已 COMPLETED 则跳过）。
     * 用条件更新抢占式退休旧任务（task_status<>SUCCESS 才退休），防止并发调度重复补偿
     * 与旧任务迟到结果污染新任务；旧任务置 FAILED 后其迟到结果会被 handleResult 按终态忽略。
     */
    @Transactional(rollbackFor = Exception.class)
    public void compensateReportTask(AiTask oldTask) {
        Long sessionId = oldTask.getBizId();
        if (sessionId == null) {
            return;
        }
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null
                || !InterviewSessionStatus.REPORTING.getCode().equals(session.getStatus())) {
            log.info("会话已离开报告阶段，跳过补偿，sessionId={}, status={}",
                    sessionId, session == null ? "会话不存在" : session.getStatus());
            return;
        }
        int retired = aiTaskMapper.update(null, new UpdateWrapper<AiTask>()
                .eq("task_id", oldTask.getTaskId())
                .apply("task_status <> {0}", TaskStatus.SUCCESS.getCode())
                .set("task_status", TaskStatus.FAILED.getCode())
                .set("error_message", "已由补偿调度重建，见同会话新任务")
                .set("finished_at", LocalDateTime.now())
                .set("updated_at", LocalDateTime.now()));
        if (retired == 0) {
            // 已被其他调度实例抢占或已成功，跳过避免重复补偿。
            log.info("旧报告任务已被抢占或已成功，跳过补偿，taskId={}", oldTask.getTaskId());
            return;
        }
        AiTask newTask = buildTask(session);
        aiTaskMapper.insert(newTask);
        schedulePublishAfterCommit(newTask, sessionId);
    }

    /**
     * 将结果消费者无法安全处理的消息收口为最终失败（REQUIRES_NEW）。
     *
     * 结果消息进入 DLQ 前必须释放会话的"报告生成中"状态：这里把报告标记 FAILED、
     * 会话推进 COMPLETED、ai_task 标记 FAILED，即使外层消费事务回滚也能提交。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markResultHandlingFailed(String taskId, String errorMessage) {
        if (taskId == null || taskId.isBlank()) {
            log.warn("报告结果无法关联任务，跳过失败收口");
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
        if (task.getBizId() != null) {
            InterviewReport report = interviewReportMapper.selectOne(
                    new LambdaQueryWrapper<InterviewReport>()
                            .eq(InterviewReport::getSessionId, task.getBizId()));
            if (report != null) {
                report.setStatus(ReportStatus.FAILED.getCode());
                interviewReportMapper.updateById(report);
            }
            completeSession(String.valueOf(task.getBizId()));
        }
    }

    // ==================== 私有辅助方法 ====================

    private AiTask buildTask(InterviewSession session) {
        return AiTask.builder()
                .taskId(UUID.randomUUID().toString())
                .userId(session.getUserId())
                .taskType(TaskType.REPORT_GENERATE.getCode())
                .taskStatus(TaskStatus.PENDING.getCode())
                .bizType(BizType.INTERVIEW_SESSION.getCode())
                .bizId(session.getId())
                .retryCount(0)
                .maxRetry(resumeProperties.getMq().getMaxScheduledRetryCount())
                .traceId(TraceIdContext.currentTraceId())
                .messageType(MESSAGE_TYPE)
                .schemaVersion(SCHEMA_VERSION)
                // sessionId 已校验归属，无注入风险；记录在请求载荷中供任务查询与审计。
                .requestPayloadJson("{\"sessionId\":\"" + session.getId() + "\"}")
                .build();
    }

    /**
     * 事务提交后投递 MQ；无活动事务时直接发送（兼容非事务调用方/单测）。
     */
    private void schedulePublishAfterCommit(AiTask task, Long sessionId) {
        Runnable publish = () -> {
            ReportGenerateMessage message = ReportGenerateMessage.builder()
                    .taskId(task.getTaskId())
                    .traceId(task.getTraceId())
                    .messageType(MESSAGE_TYPE)
                    .schemaVersion(SCHEMA_VERSION)
                    .retryCount(task.getRetryCount())
                    .createdAt(task.getCreatedAt() == null
                            ? LocalDateTime.now() : task.getCreatedAt())
                    .sessionId(String.valueOf(sessionId))
                    .build();
            boolean sent = false;
            try {
                sent = producer.sendWithRetry(
                        message,
                        resumeProperties.getMq().getMaxRetryAttempts(),
                        resumeProperties.getMq().getRetryBaseDelayMs());
            } catch (RuntimeException exception) {
                // sendWithRetry 只捕获 AmqpException；序列化等非 Amqp 异常穿透到这里，
                // 必须收口为 FAILED，否则任务停留在已提交的 PENDING 无人接管。
                log.error("报告生成任务投递异常，taskId={}，error={}",
                        task.getTaskId(), exception.getMessage());
            }
            if (!sent) {
                markDispatchFailed(task.getTaskId(), "RabbitMQ 暂时不可用，报告任务已标记失败");
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
            // 兼容非事务调用方；正式入口均会走事务分支。
            publish.run();
        }
    }

    /**
     * 更新报告内容并批量写入参考答案（成功路径）。
     */
    private void applyReportContent(
            InterviewReport report, ReportGenerateResultMessage message) {
        report.setOverallScore(message.getOverallScore());
        report.setReadinessLevel(message.getReadinessLevel());
        report.setRoleFitScore(message.getRoleFitScore());
        report.setSummary(message.getSummary());
        report.setStrengthsJson(toJson(message.getStrengths()));
        report.setWeaknessesJson(toJson(message.getWeaknesses()));
        report.setRiskPointsJson(toJson(message.getRiskPoints()));
        report.setSuggestionsJson(toJson(message.getSuggestions()));
        report.setCoverageJson(toJson(message.getCoverage()));
        report.setStatus(ReportStatus.SUCCESS.getCode());
        report.setGeneratedAt(LocalDateTime.now());
        interviewReportMapper.updateById(report);
        insertReferenceAnswers(report.getId(), report.getSessionId(), message.getReferenceAnswers());
    }

    /**
     * 逐条插入参考答案；同一报告同题已存在时由唯一索引拒绝，幂等忽略。
     */
    private void insertReferenceAnswers(
            Long reportId, Long sessionId, JsonNode referenceAnswers) {
        if (referenceAnswers == null || !referenceAnswers.isArray()) {
            throw new BusinessException("报告结果缺少参考答案");
        }
        for (JsonNode item : referenceAnswers) {
            ReferenceAnswer answer = ReferenceAnswer.builder()
                    .reportId(reportId)
                    .sessionId(sessionId)
                    .questionId(parseQuestionId(item.path("questionId").asText()))
                    .answerType(safeAnswerType(item.path("answerType").asText()))
                    .referenceContent(item.path("referenceContent").asText())
                    .keyPointsJson(toJson(item.get("keyPoints")))
                    .tradeoffsJson(toJson(item.get("tradeoffs")))
                    .build();
            try {
                referenceAnswerMapper.insert(answer);
            } catch (DuplicateKeyException duplicate) {
                // 重复生成时同题参考答案已存在，唯一索引兜底，忽略本份。
                log.info("参考答案已存在，跳过，reportId={}, questionId={}",
                        reportId, answer.getQuestionId());
            }
        }
    }

    private String safeAnswerType(String value) {
        ReferenceAnswerType type = ReferenceAnswerType.fromCode(value);
        return type == null ? ReferenceAnswerType.BASIC_KEY_POINTS.getCode() : type.getCode();
    }

    private Long parseQuestionId(String questionId) {
        try {
            return Long.parseLong(questionId);
        } catch (NumberFormatException exception) {
            throw new BusinessException("参考答案问题 ID 格式非法：" + questionId);
        }
    }

    private InterviewReport findReport(String sessionIdStr) {
        Long sessionId = parseSessionId(sessionIdStr);
        return interviewReportMapper.selectOne(
                new LambdaQueryWrapper<InterviewReport>()
                        .eq(InterviewReport::getSessionId, sessionId));
    }

    /**
     * 会话 REPORTING→COMPLETED（条件更新幂等：已 COMPLETED 时影响 0 行不报错）。
     */
    private void completeSession(String sessionIdStr) {
        Long sessionId = parseSessionId(sessionIdStr);
        sessionMapper.update(null, new LambdaUpdateWrapper<InterviewSession>()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, InterviewSessionStatus.REPORTING.getCode())
                .set(InterviewSession::getStatus, InterviewSessionStatus.COMPLETED.getCode())
                .set(InterviewSession::getUpdatedAt, LocalDateTime.now()));
    }

    /**
     * 校验报告结果消息：契约校验 + 字段完整性。
     */
    private void validateResult(ReportGenerateResultMessage message) {
        if (message == null) {
            throw new BusinessException("报告生成结果消息不能为空");
        }
        if (schemaValidator != null) {
            try {
                schemaValidator.validateReportGenerateResult(message);
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("报告生成结果契约校验失败：" + exception.getMessage());
            }
        }
        if (message.getTaskId() == null
                || message.getTraceId() == null
                || message.getMessageType() == null
                || message.getSchemaVersion() == null
                || message.getRetryCount() == null
                || message.getCreatedAt() == null
                || message.getSessionId() == null
                || message.getSuccess() == null) {
            throw new BusinessException("报告生成结果消息缺少必要字段");
        }
        if (!RESULT_MESSAGE_TYPE.equals(message.getMessageType())
                || !SCHEMA_VERSION.equals(message.getSchemaVersion())) {
            throw new BusinessException("报告生成结果消息类型或版本不正确");
        }
        if (message.getRetryCount() < 0
                || message.getRetryCount() > resumeProperties.getMq().getMaxScheduledRetryCount()) {
            throw new BusinessException("报告生成结果重试次数超出允许范围");
        }
        if (!Boolean.TRUE.equals(message.getSuccess())
                && (message.getErrorMessage() == null || message.getErrorMessage().isBlank())) {
            throw new BusinessException("报告生成失败结果缺少错误原因");
        }
    }

    /**
     * 校验结果消息与任务记录的关联关系，防止伪造或跨会话消息。
     */
    private void validateTaskRelation(AiTask task, ReportGenerateResultMessage message) {
        if (!TaskType.REPORT_GENERATE.getCode().equals(task.getTaskType())
                || !BizType.INTERVIEW_SESSION.getCode().equals(task.getBizType())
                || task.getBizId() == null
                || !String.valueOf(task.getBizId()).equals(message.getSessionId())
                || task.getTraceId() == null
                || !task.getTraceId().equals(message.getTraceId())) {
            throw new BusinessException("报告生成结果与任务会话不匹配");
        }
    }

    private Long parseSessionId(String sessionId) {
        try {
            return Long.parseLong(sessionId);
        } catch (NumberFormatException exception) {
            throw new BusinessException("面试会话 ID 格式非法：" + sessionId);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            log.warn("报告结果序列化失败，仍保存任务状态", exception);
            return null;
        }
    }
}
