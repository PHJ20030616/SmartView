package com.smartview.resume.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.resume.entity.ResumeFile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 简历文件 Mapper 接口
 *
 * 功能说明：
 * - 提供简历文件表的 CRUD 操作
 * - 继承 MyBatis-Plus 的 BaseMapper，自动获得基础方法
 * - 无需编写 XML 文件，简单查询由 MyBatis-Plus 自动实现
 *
 * 自动提供的方法（示例）：
 * - insert(ResumeFile resumeFile)：插入一条记录
 * - deleteById(Long id)：根据 ID 逻辑删除（软删除）
 * - updateById(ResumeFile resumeFile)：根据 ID 更新记录
 * - selectById(Long id)：根据 ID 查询记录（自动过滤 deleted=1）
 * - selectList(Wrapper<ResumeFile> wrapper)：根据条件查询列表
 *
 * 技术要点：
 * - @Mapper 注解标记为 MyBatis Mapper（配合 @MapperScan 自动扫描）
 * - 继承 BaseMapper<ResumeFile> 自动获得 CRUD 能力
 * - MyBatis-Plus 自动处理逻辑删除，查询时自动添加 deleted=0 条件
 * - 字段自动填充由 MyMetaObjectHandler 处理（createdAt、updatedAt）
 *
 * 使用示例：
 * <pre>
 * // 插入新记录
 * ResumeFile resumeFile = ResumeFile.builder()
 *     .userId(1L)
 *     .originalFilename("张三_Java.pdf")
 *     .objectKey("resumes/1/abc-123.pdf")
 *     .parseStatus(ParseStatus.PENDING.getCode())
 *     .build();
 * resumeFileMapper.insert(resumeFile);
 *
 * // 根据用户 ID 查询
 * List<ResumeFile> files = resumeFileMapper.selectList(
 *     new LambdaQueryWrapper<ResumeFile>()
 *         .eq(ResumeFile::getUserId, userId)
 *         .orderByDesc(ResumeFile::getUploadedAt)
 * );
 *
 * // 更新解析状态
 * resumeFile.setParseStatus(ParseStatus.SUCCESS.getCode());
 * resumeFileMapper.updateById(resumeFile);
 * </pre>
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Mapper
public interface ResumeFileMapper extends BaseMapper<ResumeFile> {
    // MyBatis-Plus 自动提供基础 CRUD 方法，无需手动定义
    // 如需复杂查询，可在此添加自定义方法或使用 QueryWrapper
}
