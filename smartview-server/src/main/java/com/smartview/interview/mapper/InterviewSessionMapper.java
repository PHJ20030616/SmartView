package com.smartview.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.interview.entity.InterviewSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 面试会话 Mapper 接口。
 *
 * 功能说明：
 * - 提供 interview_session 表的 CRUD 操作
 * - 继承 MyBatis-Plus BaseMapper，自动获得基础方法，无需编写 XML
 * - MyBatis-Plus 自动处理逻辑删除（deleted 字段），查询时自动过滤 deleted=1
 * - 字段自动填充由 MyMetaObjectHandler 处理（createdAt、updatedAt）
 * - optimisticAdvance：回答提交后的乐观锁推进（policy 4.2）
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSession> {

    /**
     * 回答提交后按乐观锁推进会话（docs/interview-policy.md 4.2）。
     *
     * version 由 SQL 自增（version = version + 1），WHERE 校验传入的旧版本号；
     * 影响行数为 0 表示版本冲突（多端/重复点击并发推进），调用方应拒绝本次更新。
     */
    @Update("UPDATE interview_session SET "
            + "current_question_id = #{session.currentQuestionId}, "
            + "current_topic = #{session.currentTopic}, "
            + "current_stage = #{session.currentStage}, "
            + "question_count = #{session.questionCount}, "
            + "status = #{session.status}, "
            + "end_reason = #{session.endReason}, "
            + "ended_at = #{session.endedAt}, "
            + "stage_coverage_json = #{session.stageCoverageJson}, "
            + "version = version + 1, "
            + "updated_at = NOW() "
            + "WHERE id = #{session.id} AND version = #{session.version} AND deleted = 0")
    int optimisticAdvance(@Param("session") InterviewSession session);
}

