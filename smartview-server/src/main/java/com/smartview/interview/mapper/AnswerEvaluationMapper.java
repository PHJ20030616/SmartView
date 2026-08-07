package com.smartview.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.interview.entity.AnswerEvaluation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回答评估 Mapper。
 *
 * 功能说明：
 * - Task 5.1 已建 answer_evaluation 表与实体，本 Mapper 补齐对快照的读取能力，
 *   供 FollowUpPoolService 在 Redis 缺失时读取最近决策快照重建候选池
 *   （interview-policy.md 3.5）
 *
 * @author SmartView Team
 * @since 2026-08-07
 */
@Mapper
public interface AnswerEvaluationMapper extends BaseMapper<AnswerEvaluation> {
}
