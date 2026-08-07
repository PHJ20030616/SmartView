package com.smartview.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.interview.entity.InterviewSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 面试会话 Mapper 接口。
 *
 * 功能说明：
 * - 提供 interview_session 表的 CRUD 操作
 * - 继承 MyBatis-Plus BaseMapper，自动获得基础方法，无需编写 XML
 * - MyBatis-Plus 自动处理逻辑删除（deleted 字段），查询时自动过滤 deleted=1
 * - 字段自动填充由 MyMetaObjectHandler 处理（createdAt、updatedAt）
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Mapper
public interface InterviewSessionMapper extends BaseMapper<InterviewSession> {
}
