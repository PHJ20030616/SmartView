package com.smartview.resume.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 画像分析状态响应。
 *
 * 该 DTO 只暴露当前用户拥有的画像在该方向下的分析状态，不接受也不回显
 * 前端提交的隔离条件。status 取值为任务状态枚举：
 * PENDING / PROCESSING / SUCCESS / FAILED / RETRYING。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProfileAnalysisStatusDto {

    /**
     * 画像分析结果 ID，分析成功时返回，否则为 null
     */
    private String profileAnalysisId;

    /**
     * 简历画像 ID
     */
    private String profileId;

    /**
     * 当前简历画像版本号
     */
    private Integer profileVersion;

    /**
     * 面试方向
     */
    private String roleDirection;

    /**
     * 画像分析任务 ID，尚未创建任务时为 null
     */
    private String taskId;

    /**
     * 画像分析任务状态
     */
    private String status;

    /**
     * 已使用的重试次数
     */
    private Integer retryCount;

    /**
     * 最近一次失败原因
     */
    private String errorMessage;

    /**
     * 状态更新时间
     */
    private LocalDateTime updatedAt;
}
