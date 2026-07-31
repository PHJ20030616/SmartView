package com.smartview.resume.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历向量入库状态响应。
 *
 * 该 DTO 只暴露当前用户拥有的画像状态，不接受也不回显前端提交的隔离条件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeVectorizationStatusDto {

    private String resumeProfileId;
    private Integer profileVersion;
    private String taskId;
    private String status;
    private Integer retryCount;
    private Integer chunksCount;
    private String errorMessage;
    private LocalDateTime updatedAt;
}
