package com.smartview.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.interview.entity.InterviewQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试问题 Mapper 接口。
 *
 * 功能说明：
 * - 提供 interview_question 表的 CRUD 操作
 * - 继承 MyBatis-Plus BaseMapper，自动获得基础方法，无需编写 XML
 * - MyBatis-Plus 自动处理逻辑删除（deleted 字段），查询时自动过滤 deleted=1
 * - 字段自动填充由 MyMetaObjectHandler 处理（createdAt、updatedAt）
 *
 * 唯一约束说明：
 * - 数据库唯一索引 uk_interview_question_session_order 保证同一会话内
 *   question_order 唯一，问题序号由业务层从 1 递增分配。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Mapper
public interface InterviewQuestionMapper extends BaseMapper<InterviewQuestion> {
}
