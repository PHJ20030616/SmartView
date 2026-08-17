package com.smartview.interview.dto;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartview.generated.web.model.AnswerHistoryItem;
import com.smartview.interview.entity.AnswerEvaluation;
import com.smartview.interview.entity.InterviewAnswer;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.enums.InterviewQuestionStatus;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewAnswerMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 已回答问题历史组装器。
 *
 * 将"已答题目 + 用户回答 + 评估"装配为对外 AnswerHistoryItem 白名单数据。
 * 供面试会话查询（InterviewSessionService）与报告查询（ReportQueryService）复用，
 * 避免同一套装配逻辑在两处复制；同时保证输出按提问序号确定有序。
 *
 * 设计取舍：
 * - 仅查 ANSWERED 状态问题（未答/跳过不进入历史），单会话问题量有限，
 *   采用分批查询在内存按 questionId 关联，避免逐题 N+1；
 * - 本类直接引用实体（InterviewQuestion/InterviewAnswer/AnswerEvaluation），
 *   返回的 AnswerHistoryItem 为生成 DTO；实体→DTO 白名单字段映射
 *   （含同名类型的区分）收敛在 InterviewSessionDtoMapper。
 *
 * @author SmartView Team
 * @since 2026-08-17
 */
@Component
public class AnswerHistoryAssembler {

    private final InterviewQuestionMapper questionMapper;
    private final InterviewAnswerMapper answerMapper;
    private final AnswerEvaluationMapper evaluationMapper;
    private final InterviewSessionDtoMapper dtoMapper;

    public AnswerHistoryAssembler(
            InterviewQuestionMapper questionMapper,
            InterviewAnswerMapper answerMapper,
            AnswerEvaluationMapper evaluationMapper,
            InterviewSessionDtoMapper dtoMapper) {
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.evaluationMapper = evaluationMapper;
        this.dtoMapper = dtoMapper;
    }

    /** 装配会话已答问题历史；仅查 ANSWERED 状态问题，按提问序号升序输出。 */
    public List<AnswerHistoryItem> load(Long sessionId) {
        List<InterviewQuestion> answered = questionMapper.selectList(
                new LambdaQueryWrapper<InterviewQuestion>()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .eq(InterviewQuestion::getStatus, InterviewQuestionStatus.ANSWERED.getCode())
                        .orderByAsc(InterviewQuestion::getQuestionOrder));
        if (answered.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, InterviewAnswer> answerByQuestionId = answerMapper.selectList(
                        new LambdaQueryWrapper<InterviewAnswer>()
                                .eq(InterviewAnswer::getSessionId, sessionId))
                .stream()
                .collect(Collectors.toMap(InterviewAnswer::getQuestionId, item -> item));
        Map<Long, AnswerEvaluation> evaluationByQuestionId = evaluationMapper.selectList(
                        new LambdaQueryWrapper<AnswerEvaluation>()
                                .eq(AnswerEvaluation::getSessionId, sessionId))
                .stream()
                .collect(Collectors.toMap(AnswerEvaluation::getQuestionId, item -> item));
        // 内存内再按提问序号排序：不依赖数据库返回顺序，确定性保证输出有序（同时使该保证可被单测直接验证）
        return answered.stream()
                .sorted(Comparator.comparing(InterviewQuestion::getQuestionOrder))
                .map(question -> dtoMapper.toAnswerHistoryItem(
                        question,
                        answerByQuestionId.get(question.getId()),
                        evaluationByQuestionId.get(question.getId())))
                .toList();
    }
}
