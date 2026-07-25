package com.smartview.resume.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.resume.dto.ResumeProfileDto;
import com.smartview.resume.dto.UpdateResumeProfileRequest;
import com.smartview.resume.service.ResumeProfileService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 简历画像控制器
 *
 * 功能说明：
 * - 提供简历画像的查询、编辑和确认接口
 * - 严格遵循 contracts/web-api/openapi.yaml 契约定义
 * - 自动进行用户认证和权限校验
 *
 * 接口列表：
 * - GET /api/resume-profiles/{profileId}：获取简历画像详情
 * - PUT /api/resume-profiles/{profileId}：编辑画像关键字段（姓名、联系方式、技能）
 * - POST /api/resume-profiles/{profileId}/confirm：确认画像
 *
 * 业务规则：
 * - 用户只能访问自己的画像
 * - 已确认的画像不允许编辑
 * - 确认操作幂等（重复确认不报错）
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Slf4j
@RestController
@RequestMapping("/api/resume-profiles")
public class ResumeProfileController {

    private final ResumeProfileService resumeProfileService;

    /**
     * 构造函数注入依赖
     *
     * @param resumeProfileService 简历画像服务
     */
    public ResumeProfileController(ResumeProfileService resumeProfileService) {
        this.resumeProfileService = resumeProfileService;
    }

    /**
     * 获取简历画像详情
     * 接口契约：GET /api/resume-profiles/{profileId}
     *
     * 功能说明：
     * - 查询指定画像的完整结构化数据
     * - 包含基本信息、教育经历、工作经历、项目经历、技能等
     * - 自动校验当前用户是否拥有该画像的访问权限
     *
     * @param profileId 画像 ID
     * @return 简历画像详情
     */
    @GetMapping("/{profileId}")
    public ApiResponse<ResumeProfileDto> getProfile(@PathVariable Long profileId) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到简历画像查询请求，userId={}, profileId={}", userId, profileId);

        ResumeProfileDto profile = resumeProfileService.getProfileDto(profileId, userId);
        return ApiResponse.success(profile);
    }

    /**
     * 编辑简历画像关键字段
     * 接口契约：PUT /api/resume-profiles/{profileId}
     *
     * 功能说明：
     * - 允许用户编辑 AI 解析出的姓名、联系方式和技能
     * - 仅更新请求中非 null 的字段（null 表示不修改）
     * - 已确认的画像不允许再编辑
     *
     * 可编辑字段（轻量编辑）：
     * - candidateName：候选人姓名
     * - contactInfo：联系方式（phone、email、wechat 等）
     * - skills：技能列表
     *
     * @param profileId 画像 ID
     * @param request   更新请求（仅包含允许编辑的字段）
     * @return 更新后的简历画像
     */
    @PutMapping("/{profileId}")
    public ApiResponse<ResumeProfileDto> updateProfile(
            @PathVariable Long profileId,
            @RequestBody UpdateResumeProfileRequest request) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到简历画像编辑请求，userId={}, profileId={}", userId, profileId);

        ResumeProfileDto profile = resumeProfileService.updateProfile(profileId, userId, request);
        return ApiResponse.success(profile);
    }

    /**
     * 确认简历画像
     * 接口契约：POST /api/resume-profiles/{profileId}/confirm
     *
     * 功能说明：
     * - 用户确认 AI 解析结果准确无误
     * - 将确认状态从 UNCONFIRMED 更新为 CONFIRMED
     * - 记录确认时间
     * - 重复确认不报错（幂等）
     *
     * 后续流程：
     * - 确认后才能进入面试环节
     * - 确认后画像用于面试出题和岗位匹配
     *
     * @param profileId 画像 ID
     * @return 确认后的简历画像
     */
    @PostMapping("/{profileId}/confirm")
    public ApiResponse<ResumeProfileDto> confirmProfile(@PathVariable Long profileId) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到简历画像确认请求，userId={}, profileId={}", userId, profileId);

        ResumeProfileDto profile = resumeProfileService.confirmProfile(profileId, userId);
        return ApiResponse.success(profile);
    }
}
