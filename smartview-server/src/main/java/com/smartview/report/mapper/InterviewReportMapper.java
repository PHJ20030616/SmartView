package com.smartview.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.report.entity.InterviewReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试报告 Mapper。
 *
 * 提供 interview_report 表的 CRUD；唯一索引 (session_id, deleted) 保证一个会话
 * 最多一份有效报告，逻辑删除由 MyBatis-Plus @TableLogic 自动处理。
 */
@Mapper
public interface InterviewReportMapper extends BaseMapper<InterviewReport> {
}
