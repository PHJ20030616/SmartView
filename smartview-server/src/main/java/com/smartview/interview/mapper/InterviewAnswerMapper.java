package com.smartview.interview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.interview.entity.InterviewAnswer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户回答 Mapper 接口。
 *
 * 功能说明：
 * - 提供 interview_answer 表的 CRUD 操作
 * - 继承 MyBatis-Plus BaseMapper，自动获得基础方法，无需编写 XML
 * - MyBatis-Plus 自动处理逻辑删除（deleted 字段），查询时自动过滤 deleted=1
 * - 字段自动填充由 MyMetaObjectHandler 处理（createdAt、updatedAt）
 *
 * 唯一约束说明：
 * - request_id 全局唯一（V8）：回答提交幂等查询的数据库兜底
 * - (question_id, deleted) 唯一（V7）：同一问题最多一份有效回答
 *
 * @author SmartView Team
 * @since 2026-08-09
 */
@Mapper
public interface InterviewAnswerMapper extends BaseMapper<InterviewAnswer> {
}
