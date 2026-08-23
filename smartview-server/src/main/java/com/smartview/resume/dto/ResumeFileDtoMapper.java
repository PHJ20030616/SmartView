package com.smartview.resume.dto;

import com.smartview.generated.web.model.ResumeFile;
import com.smartview.resume.service.ResumeProfileService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 简历文件实体与对外数据模型的映射器。
 *
 * 功能说明：
 * - 集中维护对外可公开字段，避免控制器直接序列化实体暴露内部字段
 * - 与 InterviewSessionDtoMapper 保持一致：返回契约生成的数据模型（ResumeFile），
 *   Controller 统一用 ApiResponse.success() 包装
 *
 * 设计取舍：
 * - 批量转换（toDtos）：解析成功的简历需要补充关联画像 ID（profileId），
 *   供前端从历史列表直接进入画像确认页；批量接口一次性按文件 ID 集合查询
 *   最新画像（IN 查询），避免逐条转换产生 N+1 查询
 * - 生成的枚举 fromValue 对未知值抛异常，故对 parseStatus 做安全转换，
 *   避免数据库历史脏值导致响应序列化失败
 *
 * @author SmartView Team
 * @since 2026-08-17
 */
@Component
public class ResumeFileDtoMapper {

    private final ResumeProfileService resumeProfileService;

    public ResumeFileDtoMapper(ResumeProfileService resumeProfileService) {
        this.resumeProfileService = resumeProfileService;
    }

    /**
     * 批量转换简历文件实体列表为契约 DTO 列表。
     *
     * @param entities 简历文件实体列表
     * @param userId   当前登录用户 ID（画像查询按用户维度限定）
     * @return 契约 ResumeFile 数据模型列表（parseStatus 未知值时安全缺省）
     */
    public List<ResumeFile> toDtos(List<com.smartview.resume.entity.ResumeFile> entities, Long userId) {
        // 只对解析成功的文件查询画像 ID，其余文件无需画像关联
        List<Long> successFileIds = entities.stream()
                .filter(entity -> "SUCCESS".equals(entity.getParseStatus()))
                .map(com.smartview.resume.entity.ResumeFile::getId)
                .toList();
        // 一次 IN 批量查询所有最新画像，替代逐条查询（避免 N+1）
        Map<Long, Long> profileIdByFileId = successFileIds.isEmpty()
                ? Map.of()
                : resumeProfileService.findLatestProfileIdsByFileIds(successFileIds, userId);

        return entities.stream()
                .map(entity -> toDto(entity, profileIdByFileId.get(entity.getId())))
                .toList();
    }

    /**
     * 单条转换：画像 ID 由调用方通过批量查询结果注入，避免此处再次查询数据库。
     *
     * @param entity    简历文件实体
     * @param profileId 该文件的最新画像 ID（可为 null，表示尚无画像）
     * @return 契约 ResumeFile 数据模型
     */
    private ResumeFile toDto(
            com.smartview.resume.entity.ResumeFile entity, Long profileId) {
        return new ResumeFile(
                entity.getId().toString(),
                entity.getUserId().toString(),
                entity.getOriginalFilename(),
                safeParseStatus(entity.getParseStatus()))
                .fileSize(entity.getFileSize())
                .mimeType(entity.getMimeType())
                .parseTaskId(entity.getParseTaskId())
                .profileId(profileId == null ? null : profileId.toString())
                .errorMessage(entity.getErrorMessage())
                .uploadedAt(toOffsetDateTime(entity.getUploadedAt()))
                .createdAt(toOffsetDateTime(entity.getCreatedAt()));
    }

    /**
     * 解析状态安全转换：数据库历史脏值/未知值返回 null（响应缺省该字段），
     * 避免整体序列化 500，与既有 DTO 映射器的安全转换策略一致。
     */
    private ResumeFile.ParseStatusEnum safeParseStatus(String code) {
        if (code == null) {
            return null;
        }
        try {
            return ResumeFile.ParseStatusEnum.fromValue(code);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private OffsetDateTime toOffsetDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        // 数据库存储不带时区的本地时间，响应时附加当前服务时区以符合 OpenAPI date-time 格式
        return dateTime.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
