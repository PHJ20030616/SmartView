package com.smartview.interview.dto;

import com.smartview.generated.web.model.AnswerEvaluation;
import com.smartview.generated.web.model.AnswerHistoryItem;
import com.smartview.generated.web.model.InterviewQuestion;
import com.smartview.generated.web.model.InterviewSession;
import com.smartview.generated.web.model.InterviewSessionSummary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 面试会话/问题实体与对外数据模型的映射器。
 *
 * 功能说明：
 * - 集中维护对外可公开字段，避免控制器直接序列化实体暴露内部字段
 * - 与 UserDtoMapper 保持一致：返回契约数据模型（InterviewSession），
 *   Controller 统一用 ApiResponse.success() 包装，避免重复包裹
 *
 * 设计取舍：
 * - 实体与生成 DTO 类名相同（InterviewSession / InterviewQuestion），
 *   参数类型使用实体全限定名、DTO 使用 import 别名，避免命名冲突
 * - 生成的枚举 fromValue 对未知值抛异常，故对可空枚举字段做安全转换，
 *   避免数据库历史脏值或空值导致响应序列化失败
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Component
public class InterviewSessionDtoMapper {

    /**
     * 组装会话数据模型（含当前问题）。
     *
     * @param session          面试会话实体
     * @param currentQuestion  当前待答问题，可为 null（会话尚无题目）
     * @return 契约 InterviewSession 数据模型
     */
    public InterviewSession toResponse(
            com.smartview.interview.entity.InterviewSession session,
            com.smartview.interview.entity.InterviewQuestion currentQuestion) {
        return new InterviewSession(
                session.getId().toString(),
                session.getUserId().toString(),
                session.getResumeProfileId().toString(),
                safeDirection(session.getRoleDirection()),
                safeSessionStatus(session.getStatus()))
                .profileAnalysisId(session.getProfileAnalysisId() == null
                        ? null
                        : session.getProfileAnalysisId().toString())
                .questionCount(session.getQuestionCount())
                .expectedMinQuestions(session.getExpectedMinQuestions())
                .expectedMaxQuestions(session.getExpectedMaxQuestions())
                .startedAt(toOffsetDateTime(session.getStartedAt()))
                .endedAt(toOffsetDateTime(session.getEndedAt()))
                .createdAt(toOffsetDateTime(session.getCreatedAt()))
                .currentQuestion(toQuestion(currentQuestion));
    }

    public InterviewQuestion toQuestion(com.smartview.interview.entity.InterviewQuestion entity) {
        if (entity == null) {
            return null;
        }
        return new InterviewQuestion(
                entity.getId().toString(),
                entity.getSessionId().toString(),
                entity.getQuestionOrder(),
                entity.getQuestionText())
                .parentQuestionId(entity.getParentQuestionId() == null
                        ? null
                        : entity.getParentQuestionId().toString())
                .questionType(safeQuestionType(entity.getQuestionType()))
                .topic(entity.getTopic())
                .sourceType(safeSourceType(entity.getSourceType()))
                .status(safeStatus(entity.getStatus()))
                .askedAt(toOffsetDateTime(entity.getAskedAt()));
    }

    private InterviewQuestion.QuestionTypeEnum safeQuestionType(String code) {
        return code == null ? null : InterviewQuestion.QuestionTypeEnum.fromValue(code);
    }

    /**
     * 组装回答历史项（问题 + 回答 + 评估）。
     *
     * 仅映射对外白名单字段：问题复用 toQuestion，评估可为空（如未评估题目）。
     * 回答实体必不为空（历史仅含已回答问题）。
     */
    public AnswerHistoryItem toAnswerHistoryItem(
            com.smartview.interview.entity.InterviewQuestion question,
            com.smartview.interview.entity.InterviewAnswer answer,
            com.smartview.interview.entity.AnswerEvaluation evaluation) {
        return new AnswerHistoryItem(toQuestion(question), answer.getAnswerText())
                .durationSeconds(answer.getDurationSeconds())
                .submittedAt(toOffsetDateTime(answer.getSubmittedAt()))
                .evaluation(toEvaluation(evaluation));
    }

    /**
     * 评估实体转 DTO：score/level 为必填受控字段，evaluationText 可选。
     * 未知等级值安全缺省（返回 null），避免历史脏值导致 getSession 序列化 500。
     */
    private AnswerEvaluation toEvaluation(com.smartview.interview.entity.AnswerEvaluation entity) {
        if (entity == null) {
            return null;
        }
        AnswerEvaluation.LevelEnum level = null;
        if (entity.getLevel() != null) {
            try {
                level = AnswerEvaluation.LevelEnum.fromValue(entity.getLevel());
            } catch (IllegalArgumentException exception) {
                // 未知等级值：响应缺省该字段而非整体 500（与既有安全转换一致）
            }
        }
        return new AnswerEvaluation(entity.getScore(), level)
                .id(entity.getId() == null ? null : String.valueOf(entity.getId()))
                .evaluationText(entity.getEvaluationText());
    }

    private InterviewQuestion.SourceTypeEnum safeSourceType(String code) {
        return code == null ? null : InterviewQuestion.SourceTypeEnum.fromValue(code);
    }

    private InterviewQuestion.StatusEnum safeStatus(String code) {
        return code == null ? null : InterviewQuestion.StatusEnum.fromValue(code);
    }

    /**
     * 组装会话历史摘要（Task 7.1 列表专用轻量模型）。
     *
     * 与 toResponse 的区别：摘要不含 currentQuestion 与 answers，
     * 避免历史列表加载全部问答详情；附带报告关联信息（reportId/reportStatus），
     * 供前端决定"查看报告"入口是否可用（报告生成中/成功/失败/无报告）。
     *
     * @param session 面试会话实体
     * @param report  关联报告实体，可为 null（会话尚未生成报告）
     * @return 契约 InterviewSessionSummary 数据模型
     */
    public InterviewSessionSummary toSummary(
            com.smartview.interview.entity.InterviewSession session,
            com.smartview.report.entity.InterviewReport report) {
        return new InterviewSessionSummary(
                session.getId().toString(),
                session.getUserId().toString(),
                session.getResumeProfileId().toString(),
                safeSummaryDirection(session.getRoleDirection()),
                safeSummaryStatus(session.getStatus()))
                .questionCount(session.getQuestionCount())
                .expectedMinQuestions(session.getExpectedMinQuestions())
                .expectedMaxQuestions(session.getExpectedMaxQuestions())
                .reportId(report == null ? null : report.getId().toString())
                .reportStatus(report == null ? null : safeReportStatus(report.getStatus()))
                .startedAt(toOffsetDateTime(session.getStartedAt()))
                .endedAt(toOffsetDateTime(session.getEndedAt()))
                .createdAt(toOffsetDateTime(session.getCreatedAt()));
    }

    /**
     * 摘要模型的方向/状态安全转换：生成模型为 InterviewSession 与
     * InterviewSessionSummary 各自生成独立的枚举类，必须分别转换；
     * 未知值返回 null（响应缺省该字段而非整体 500）。
     */
    private InterviewSessionSummary.RoleDirectionEnum safeSummaryDirection(String code) {
        if (code == null) {
            return null;
        }
        try {
            return InterviewSessionSummary.RoleDirectionEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private InterviewSessionSummary.StatusEnum safeSummaryStatus(String code) {
        if (code == null) {
            return null;
        }
        try {
            return InterviewSessionSummary.StatusEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 报告状态安全转换：未知值返回 null（响应缺省该字段而非整体 500）。
     * 生成模型的 ReportStatusEnum.fromValue 对未知值本身返回 null，此处
     * 显式 try-catch 仅为与既有安全转换风格保持一致。
     */
    private InterviewSessionSummary.ReportStatusEnum safeReportStatus(String code) {
        if (code == null) {
            return null;
        }
        try {
            return InterviewSessionSummary.ReportStatusEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 方向/会话状态为必填受控字段，但为防止未来枚举未同步导致 getSession 序列化
     * 抛异常，同样做安全转换（未知值返回 null，响应缺省该字段而非整体 500）。
     */
    private InterviewSession.RoleDirectionEnum safeDirection(String code) {
        return code == null ? null : InterviewSession.RoleDirectionEnum.fromValue(code);
    }

    private InterviewSession.StatusEnum safeSessionStatus(String code) {
        return code == null ? null : InterviewSession.StatusEnum.fromValue(code);
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        // 数据库存储不带时区的本地时间，响应时附加当前服务时区以符合 OpenAPI date-time 格式。
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
