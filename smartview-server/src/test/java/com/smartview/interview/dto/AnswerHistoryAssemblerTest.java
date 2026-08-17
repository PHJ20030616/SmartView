package com.smartview.interview.dto;

import com.smartview.interview.entity.AnswerEvaluation;
import com.smartview.interview.entity.InterviewAnswer;
import com.smartview.interview.entity.InterviewQuestion;
import com.smartview.interview.mapper.AnswerEvaluationMapper;
import com.smartview.interview.mapper.InterviewAnswerMapper;
import com.smartview.interview.mapper.InterviewQuestionMapper;
import com.smartview.generated.web.model.AnswerHistoryItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 回答历史组装器测试。
 *
 * 覆盖抽取自 InterviewSessionService.loadAnswerHistory 的行为：
 * 1. 乱序 mock 输入 → 输出按 questionOrder 升序；
 * 2. 无评估题目 → evaluation 为 null；
 * 3. 无已答题 → 返回空 list。
 * 断言目标是生成 DTO（com.smartview.generated.web.model.AnswerHistoryItem），
 * mock 输入为实体（com.smartview.interview.entity.*）。
 */
@ExtendWith(MockitoExtension.class)
class AnswerHistoryAssemblerTest {

    @Mock private InterviewQuestionMapper questionMapper;
    @Mock private InterviewAnswerMapper answerMapper;
    @Mock private AnswerEvaluationMapper evaluationMapper;

    @Test
    void load_按提问序号排序且缺省未评估() {
        // mock 乱序返回（问题二在前），验证内存重排序
        when(questionMapper.selectList(any())).thenReturn(List.of(
                InterviewQuestion.builder().id(12L).sessionId(1L).questionOrder(2)
                        .questionText("问题二").status("ANSWERED").build(),
                InterviewQuestion.builder().id(11L).sessionId(1L).questionOrder(1)
                        .questionText("问题一").status("ANSWERED").build()));
        when(answerMapper.selectList(any())).thenReturn(List.of(
                InterviewAnswer.builder().id(101L).questionId(11L).answerText("回答一")
                        .submittedAt(LocalDateTime.of(2026, 8, 9, 10, 0)).build(),
                InterviewAnswer.builder().id(102L).questionId(12L).answerText("回答二")
                        .submittedAt(LocalDateTime.of(2026, 8, 9, 10, 1)).build()));
        when(evaluationMapper.selectList(any())).thenReturn(List.of(
                AnswerEvaluation.builder().id(201L).questionId(11L).score(85).level("GOOD")
                        .evaluationText("要点清晰").build()));

        AnswerHistoryAssembler assembler = new AnswerHistoryAssembler(
                questionMapper, answerMapper, evaluationMapper, new InterviewSessionDtoMapper());
        List<AnswerHistoryItem> items = assembler.load(1L);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getQuestion().getQuestionText()).isEqualTo("问题一");
        assertThat(items.get(0).getEvaluation().getScore()).isEqualTo(85);
        assertThat(items.get(1).getEvaluation()).isNull();
    }

    @Test
    void load_无已答题返回空列表() {
        when(questionMapper.selectList(any())).thenReturn(List.of());
        AnswerHistoryAssembler assembler = new AnswerHistoryAssembler(
                questionMapper, answerMapper, evaluationMapper, new InterviewSessionDtoMapper());
        assertThat(assembler.load(1L)).isEmpty();
    }
}
