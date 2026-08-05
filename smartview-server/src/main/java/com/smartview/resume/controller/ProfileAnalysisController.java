package com.smartview.resume.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.resume.dto.ProfileAnalysisStatusDto;
import com.smartview.resume.dto.StartProfileAnalysisRequest;
import com.smartview.resume.service.ProfileAnalysisTaskService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 画像分析控制器
 *
 * 功能说明：
 * - 用户选择面试方向后触发该方向画像分析
 * - 轮询画像分析状态，成功后才允许开始面试
 * - 分析失败时允许用户重试
 *
 * 接口列表：
 * - POST /api/profile-analyses：触发或获取方向画像分析（幂等）
 * - GET /api/profile-analyses/{profileId}?roleDirection=：查询画像分析状态
 * - POST /api/profile-analyses/{profileId}/retry?roleDirection=：失败后重试
 *
 * 业务规则：
 * - 创建任务前校验简历向量已成功入库
 * - 隔离条件由 Spring 根据认证用户和路径中的画像 ID 生成
 * - 同一简历版本、同一方向只有一份有效画像分析
 *
 * @author SmartView Team
 * @since 2026-08-03
 */
@Slf4j
@RestController
@RequestMapping("/api/profile-analyses")
public class ProfileAnalysisController {

    private final ProfileAnalysisTaskService profileAnalysisTaskService;

    public ProfileAnalysisController(ProfileAnalysisTaskService profileAnalysisTaskService) {
        this.profileAnalysisTaskService = profileAnalysisTaskService;
    }

    /**
     * 触发或获取方向画像分析。
     * 接口契约：POST /api/profile-analyses
     *
     * 幂等语义：已有成功分析直接返回 SUCCESS；已有进行中任务返回任务状态；
     * 分析失败时新建任务重新分析。
     */
    @PostMapping
    public ApiResponse<ProfileAnalysisStatusDto> ensureProfileAnalysis(
            @RequestBody StartProfileAnalysisRequest request) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到画像分析触发请求，userId={}, profileId={}, direction={}",
                userId, request.getProfileId(), request.getRoleDirection());
        ProfileAnalysisStatusDto status = profileAnalysisTaskService.ensureTask(
                request.getProfileId(), userId, request.getRoleDirection());
        return ApiResponse.success(status);
    }

    /**
     * 查询画像分析状态。
     * 接口契约：GET /api/profile-analyses/{profileId}?roleDirection=
     */
    @GetMapping("/{profileId}")
    public ApiResponse<ProfileAnalysisStatusDto> getProfileAnalysisStatus(
            @PathVariable Long profileId,
            @RequestParam String roleDirection) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到画像分析状态查询请求，userId={}, profileId={}, direction={}",
                userId, profileId, roleDirection);
        ProfileAnalysisStatusDto status = profileAnalysisTaskService.getStatus(
                profileId, userId, roleDirection);
        return ApiResponse.success(status);
    }

    /**
     * 重试方向画像分析。
     * 接口契约：POST /api/profile-analyses/{profileId}/retry?roleDirection=
     *
     * 画像分析失败时允许用户重试，重试期间不允许开始面试。
     */
    @PostMapping("/{profileId}/retry")
    public ApiResponse<ProfileAnalysisStatusDto> retryProfileAnalysis(
            @PathVariable Long profileId,
            @RequestParam String roleDirection) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到画像分析重试请求，userId={}, profileId={}, direction={}",
                userId, profileId, roleDirection);
        ProfileAnalysisStatusDto status = profileAnalysisTaskService.retry(
                profileId, userId, roleDirection);
        return ApiResponse.success(status);
    }
}
