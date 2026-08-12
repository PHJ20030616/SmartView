package com.smartview.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.report.entity.ReferenceAnswer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 参考答案 Mapper。
 *
 * 提供 reference_answer 表的 CRUD；唯一索引 (report_id, question_id, deleted)
 * 保证同一报告内每道题至多一份有效参考答案。
 */
@Mapper
public interface ReferenceAnswerMapper extends BaseMapper<ReferenceAnswer> {
}
