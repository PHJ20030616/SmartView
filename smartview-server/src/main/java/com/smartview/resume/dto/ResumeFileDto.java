package com.smartview.resume.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历文件响应 DTO
 *
 * 功能说明：
 * - 简历文件信息的数据传输对象
 * - 用于 Controller 层向前端返回简历文件信息
 * - 遵循 contracts/web-api/openapi.yaml 中的 ResumeFile schema 定义
 *
 * 注意：
 * - 这是临时 DTO，后续应从契约生成（通过 OpenAPI Generator）
 * - 字段定义严格遵循契约，不可随意修改
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeFileDto {

    /**
     * 简历文件 ID
     */
    private String id;

    /**
     * 所属用户 ID
     */
    private String userId;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件 MIME 类型
     */
    private String mimeType;

    /**
     * 解析状态
     * 枚举值：PENDING, PROCESSING, SUCCESS, FAILED
     */
    private String parseStatus;

    /**
     * 解析任务 ID
     */
    private String parseTaskId;

    /**
     * 解析失败原因
     */
    private String errorMessage;

    /**
     * 上传时间
     */
    private LocalDateTime uploadedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
