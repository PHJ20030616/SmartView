package com.smartview.resume.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历文件实体类
 *
 * 功能说明：
 * - 映射数据库 resume_file 表
 * - 存储用户上传的简历文件元数据（原始文件名、MinIO存储路径、文件哈希等）
 * - 记录AI解析状态和解析任务关联
 * - 支持软删除（deleted 字段配合 @TableLogic）
 * - 支持字段自动填充（createdAt 和 updatedAt 配合 MyMetaObjectHandler）
 *
 * 技术要点：
 * - @TableName("resume_file")：映射到 resume_file 表
 * - @TableId(type = IdType.AUTO)：主键自增策略
 * - @TableLogic：标记 deleted 字段为逻辑删除字段（0=未删除，1=已删除）
 * - @TableField(fill = FieldFill.INSERT)：插入时自动填充 createdAt 和 uploadedAt
 * - @TableField(fill = FieldFill.INSERT_UPDATE)：插入和更新时自动填充 updatedAt
 *
 * 业务流程：
 * 1. 用户上传简历文件 → 文件存储到 MinIO → 创建 ResumeFile 记录（parse_status=PENDING）
 * 2. 触发 AI 解析任务 → 更新 parse_task_id → 更新 parse_status=PROCESSING
 * 3. AI 解析完成 → 创建 ResumeProfile 记录 → 更新 parse_status=SUCCESS
 * 4. AI 解析失败 → 记录 error_message → 更新 parse_status=FAILED
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("resume_file")
public class ResumeFile {

    /**
     * 简历文件ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属用户ID
     * 外键关联 user 表，用于权限控制和用户维度查询
     */
    private Long userId;

    /**
     * 用户上传时的原始文件名
     * 用于前端展示和下载时恢复原文件名（如"张三_Java开发_2年经验.pdf"）
     */
    private String originalFilename;

    /**
     * MinIO 中的文件对象 Key
     * 格式示例：resumes/{user_id}/{uuid}.pdf
     * 用于从对象存储服务检索文件内容
     */
    private String objectKey;

    /**
     * 文件 SHA-256 哈希值
     * 用途：
     * 1. 去重检测：避免同一用户重复上传相同文件
     * 2. 文件完整性校验：下载时验证文件未被篡改
     * 3. 审计追踪：记录文件的唯一指纹
     */
    private String fileHash;

    /**
     * 文件大小，单位字节
     * 用途：
     * 1. 存储配额管理：限制单个文件或用户总容量
     * 2. 前端展示：向用户显示文件大小
     * 3. 费用计算：按存储量收费
     */
    private Long fileSize;

    /**
     * 文件 MIME 类型
     * 第一版主要为 application/pdf，后续可能支持：
     * - application/vnd.openxmlformats-officedocument.wordprocessingml.document (docx)
     * - application/msword (doc)
     * - image/png, image/jpeg (图片格式简历，需OCR)
     */
    private String mimeType;

    /**
     * 解析状态
     * 枚举值：
     * - PENDING：待解析（文件刚上传，尚未触发AI解析）
     * - PROCESSING：解析中（AI服务正在处理）
     * - SUCCESS：解析成功（已生成 ResumeProfile）
     * - FAILED：解析失败（错误信息见 error_message 字段）
     *
     * 状态流转：PENDING → PROCESSING → SUCCESS/FAILED
     */
    private String parseStatus;

    /**
     * 对应的 AI 解析任务 ID
     * 关联 ai_task 表的 task_id 字段，用于：
     * 1. 追踪解析任务的执行状态
     * 2. 失败时查看详细错误日志
     * 3. 支持任务重试
     */
    private String parseTaskId;

    /**
     * 解析失败原因
     * 记录 AI 服务返回的错误信息，用于向用户展示失败原因，例如：
     * - "文件格式不支持"
     * - "PDF损坏，无法提取文本"
     * - "简历内容不完整，缺少关键信息"
     * - "AI服务超时"
     */
    private String errorMessage;

    /**
     * 文件上传时间
     * 用于排序展示（最新上传的简历排在前面）和统计分析
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime uploadedAt;

    /**
     * 记录创建时间
     * 由 MyMetaObjectHandler 在插入时自动填充，业务代码无需手动设置
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间
     * 由 MyMetaObjectHandler 在插入和更新时自动填充，业务代码无需手动设置
     * 每次状态变更（如 PENDING → PROCESSING）都会自动更新此字段
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 软删除标记：0=未删除，1=已删除
     * 配合 @TableLogic 注解实现逻辑删除：
     * - 查询时自动添加 deleted=0 条件
     * - 调用 deleteById 时执行 UPDATE 而非 DELETE
     * - 删除文件时，对应的 MinIO 对象也应标记删除或异步清理
     */
    @TableLogic
    private Integer deleted;
}
