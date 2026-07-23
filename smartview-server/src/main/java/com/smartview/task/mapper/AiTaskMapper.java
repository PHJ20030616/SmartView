package com.smartview.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.task.entity.AiTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 任务 Mapper 接口
 *
 * 功能说明：
 * - 提供 AI 任务表的 CRUD 操作
 * - 继承 MyBatis-Plus 的 BaseMapper，自动获得基础方法
 * - 无需编写 XML 文件，简单查询由 MyBatis-Plus 自动实现
 *
 * 自动提供的方法（示例）：
 * - insert(AiTask aiTask)：插入一条记录
 * - deleteById(Long id)：根据 ID 逻辑删除（软删除）
 * - updateById(AiTask aiTask)：根据 ID 更新记录
 * - selectById(Long id)：根据 ID 查询记录（自动过滤 deleted=1）
 * - selectList(Wrapper<AiTask> wrapper)：根据条件查询列表
 *
 * 技术要点：
 * - @Mapper 注解标记为 MyBatis Mapper（配合 @MapperScan 自动扫描）
 * - 继承 BaseMapper<AiTask> 自动获得 CRUD 能力
 * - MyBatis-Plus 自动处理逻辑删除，查询时自动添加 deleted=0 条件
 * - 字段自动填充由 MyMetaObjectHandler 处理（createdAt、updatedAt）
 *
 * 使用示例：
 * <pre>
 * // 创建新任务
 * AiTask aiTask = AiTask.builder()
 *     .taskId(UUID.randomUUID().toString())
 *     .userId(1L)
 *     .taskType(TaskType.RESUME_PARSE.getCode())
 *     .taskStatus(TaskStatus.PENDING.getCode())
 *     .bizType(BizType.RESUME_FILE.getCode())
 *     .bizId(resumeFileId)
 *     .retryCount(0)
 *     .maxRetry(3)
 *     .build();
 * aiTaskMapper.insert(aiTask);
 *
 * // 查询失败任务（用于定时重试）
 * List<AiTask> failedTasks = aiTaskMapper.selectList(
 *     new LambdaQueryWrapper<AiTask>()
 *         .eq(AiTask::getTaskType, TaskType.RESUME_PARSE.getCode())
 *         .eq(AiTask::getTaskStatus, TaskStatus.FAILED.getCode())
 *         .lt(AiTask::getRetryCount, 3)
 *         .orderByAsc(AiTask::getCreatedAt)
 *         .last("LIMIT 100")
 * );
 *
 * // 更新任务状态
 * aiTask.setTaskStatus(TaskStatus.SUCCESS.getCode());
 * aiTask.setFinishedAt(LocalDateTime.now());
 * aiTaskMapper.updateById(aiTask);
 * </pre>
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Mapper
public interface AiTaskMapper extends BaseMapper<AiTask> {
    // MyBatis-Plus 自动提供基础 CRUD 方法，无需手动定义
    // 如需复杂查询，可在此添加自定义方法或使用 QueryWrapper
}
