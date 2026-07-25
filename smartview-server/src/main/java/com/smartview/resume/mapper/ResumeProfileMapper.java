package com.smartview.resume.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.resume.entity.ResumeProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 简历画像 Mapper 接口
 *
 * 功能说明：
 * - 提供简历画像表的 CRUD 操作
 * - 继承 MyBatis-Plus 的 BaseMapper，自动获得基础方法
 * - 无需编写 XML 文件，简单查询由 MyBatis-Plus 自动实现
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Mapper
public interface ResumeProfileMapper extends BaseMapper<ResumeProfile> {
}
