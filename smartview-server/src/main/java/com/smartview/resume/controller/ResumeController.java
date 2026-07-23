package com.smartview.resume.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.resume.dto.ResumeFileDto;
import com.smartview.resume.service.ResumeFileService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历文件控制器
 *
 * 功能说明：
 * - 提供简历文件上传、查询接口
 * - 严格遵循 contracts/web-api/openapi.yaml 契约定义
 * - 自动进行用户认证和权限校验
 *
 * 接口列表：
 * - POST /api/resumes：上传简历文件
 * - GET /api/resumes/{resumeFileId}：获取简历文件信息
 *
 * 技术要点：
 * - 使用 MultipartFile 接收文件上传
 * - 从 SecurityContextHolder 获取当前登录用户 ID
 * - 返回契约生成的 DTO（ResumeFile）
 * - 全局异常处理器统一处理业务异常
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Slf4j
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeFileService resumeFileService;

    /**
     * 构造函数注入依赖
     *
     * @param resumeFileService 简历文件服务
     */
    public ResumeController(ResumeFileService resumeFileService) {
        this.resumeFileService = resumeFileService;
    }

    /**
     * 上传简历文件
     * 接口契约：POST /api/resumes
     *
     * 功能说明：
     * 1. 校验文件类型（仅支持 PDF）和大小（最大 10MB，可配置）
     * 2. 上传文件到 MinIO
     * 3. 写入 resume_file 和 ai_task 表
     * 4. 投递 RabbitMQ 简历解析任务（带立即重试）
     * 5. 返回简历文件信息（包含 parseStatus=PENDING）
     *
     * 前端处理：
     * - 上传成功后立即开始轮询 GET /api/resumes/{resumeFileId}
     * - 每 2 秒轮询一次，最多轮询 2 分钟
     * - parseStatus 变为 SUCCESS 时跳转到简历画像页面
     * - parseStatus 变为 FAILED 时提示用户重新上传
     *
     * @param file 上传的 PDF 文件
     * @return 简历文件信息
     */
    @PostMapping
    public ApiResponse<ResumeFileDto> uploadResume(@RequestParam("file") MultipartFile file) {
        // 获取当前登录用户 ID
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到简历上传请求，userId={}, filename={}, size={}",
                userId, file.getOriginalFilename(), file.getSize());

        // 调用服务层上传简历
        com.smartview.resume.entity.ResumeFile resumeFileEntity = resumeFileService.uploadResume(file, userId);

        // 转换为契约 DTO
        ResumeFileDto resumeFileDto = convertToDto(resumeFileEntity);

        log.info("简历上传成功，userId={}, resumeFileId={}, parseStatus={}",
                userId, resumeFileEntity.getId(), resumeFileEntity.getParseStatus());

        return ApiResponse.success(resumeFileDto);
    }

    /**
     * 获取简历文件信息
     * 接口契约：GET /api/resumes/{resumeFileId}
     *
     * 功能说明：
     * - 查询简历文件的当前状态（解析状态、错误信息等）
     * - 前端通过轮询此接口获取解析进度
     *
     * @param resumeFileId 简历文件 ID
     * @return 简历文件信息
     */
    @GetMapping("/{resumeFileId}")
    public ApiResponse<ResumeFileDto> getResumeFile(@PathVariable String resumeFileId) {
        // 获取当前登录用户 ID（用于权限校验）
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到简历文件查询请求，userId={}, resumeFileId={}", userId, resumeFileId);

        // 查询简历文件
        com.smartview.resume.entity.ResumeFile resumeFileEntity = resumeFileService.getResumeFile(Long.parseLong(resumeFileId));

        // 权限校验：只能查询自己的简历
        if (!resumeFileEntity.getUserId().equals(userId)) {
            log.warn("用户尝试访问他人简历，userId={}, resumeFileId={}, ownerId={}",
                    userId, resumeFileId, resumeFileEntity.getUserId());
            throw new com.smartview.common.exception.BusinessException(
                    com.smartview.common.api.ResponseCode.FORBIDDEN,
                    "无权访问该简历文件"
            );
        }

        // 转换为契约 DTO
        ResumeFileDto resumeFileDto = convertToDto(resumeFileEntity);

        return ApiResponse.success(resumeFileDto);
    }

    /**
     * 将实体类转换为契约 DTO
     * 映射关系遵循 contracts/web-api/openapi.yaml 中的 ResumeFile schema
     *
     * @param entity 实体类
     * @return 契约 DTO
     */
    private ResumeFileDto convertToDto(com.smartview.resume.entity.ResumeFile entity) {
        return ResumeFileDto.builder()
                .id(entity.getId().toString())
                .userId(entity.getUserId().toString())
                .originalFilename(entity.getOriginalFilename())
                .fileSize(entity.getFileSize())
                .mimeType(entity.getMimeType())
                .parseStatus(entity.getParseStatus())
                .parseTaskId(entity.getParseTaskId())
                .errorMessage(entity.getErrorMessage())
                .uploadedAt(entity.getUploadedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
