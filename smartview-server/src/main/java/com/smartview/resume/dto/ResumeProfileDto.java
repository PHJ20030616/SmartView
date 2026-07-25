package com.smartview.resume.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 简历画像响应 DTO
 *
 * 功能说明：
 * - 简历画像信息的数据传输对象
 * - 用于 Controller 层向前端返回简历画像数据
 * - 遵循 contracts/web-api/openapi.yaml 中的 ResumeProfile schema 定义
 *
 * 注意：
 * - 这是临时 DTO，后续应从契约生成（通过 OpenAPI Generator）
 * - 字段定义严格遵循契约，不可随意修改
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeProfileDto {

    /** 简历画像 ID */
    private String id;

    /** 所属用户 ID */
    private String userId;

    /** 对应简历文件 ID */
    private String resumeFileId;

    /** 候选人姓名 */
    private String candidateName;

    /** 联系方式（Map 形式，从 contact_info_json 反序列化） */
    private Map<String, Object> contactInfo;

    /** 教育经历（List<Map> 形式，从 education_json 反序列化） */
    private List<Map<String, Object>> education;

    /** 工作经历（List<Map> 形式，从 work_experience_json 反序列化） */
    private List<Map<String, Object>> workExperience;

    /** 项目经历（List<Map> 形式，从 project_experience_json 反序列化） */
    private List<Map<String, Object>> projectExperience;

    /** 技能列表（从 skills_json 反序列化） */
    private List<String> skills;

    /** 简历原文 */
    private String rawText;

    /** 确认状态：UNCONFIRMED | CONFIRMED */
    private String confirmStatus;

    /** 确认时间 */
    private LocalDateTime confirmedAt;

    /** 画像版本号 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
