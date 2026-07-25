package com.smartview.resume.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 更新简历画像请求 DTO
 *
 * 功能说明：
 * - 前端确认页编辑简历画像关键字段的请求体
 * - 遵循 contracts/web-api/openapi.yaml 中的 UpdateResumeProfileRequest schema 定义
 * - 仅允许编辑姓名、联系方式和技能三个关键字段，
 *   教育经历、工作经历等项目由 AI 解析保证准确性，不允许前端编辑
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateResumeProfileRequest {

    /** 候选人姓名（可选，不传则不更新） */
    private String candidateName;

    /** 联系方式（可选，不传则不更新） */
    private Map<String, Object> contactInfo;

    /** 技能列表（可选，不传则不更新） */
    private List<String> skills;
}
