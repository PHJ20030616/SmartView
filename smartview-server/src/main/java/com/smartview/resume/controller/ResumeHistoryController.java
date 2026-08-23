package com.smartview.resume.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.ResumeFile;
import com.smartview.generated.web.model.ResumeFilePage;
import com.smartview.resume.dto.ResumeFileDtoMapper;
import com.smartview.resume.service.ResumeFileService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 简历历史控制器（Task 7.1）。
 *
 * 功能说明：
 * - GET /api/resumes：分页查询当前登录用户的简历文件历史，按上传时间倒序
 * - 只返回当前用户数据；已软删除记录由 @TableLogic 在查询层自动过滤
 * - 解析成功的简历附带 profileId，前端可据此进入画像确认页
 *
 * 设计取舍：
 * - 与 ResumeController 共用 /api/resumes 路径前缀：Spring MVC 按"方法+路径"
 *   精确匹配，GET 列表与 POST 上传/GET、DELETE 单条互不冲突
 * - 分页参数做防御性收敛（page>=1、1<=size<=50），避免非法入参导致
 *   全表扫描或超大分页
 *
 * @author SmartView Team
 * @since 2026-08-17
 */
@Slf4j
@RestController
@RequestMapping("/api/resumes")
public class ResumeHistoryController {

    /** 每页最大条数，与契约 size.maximum=50 保持一致 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 页码上界：防止超大页码触发 MySQL 大偏移扫描（LIMIT offset 需遍历 offset 行） */
    private static final int MAX_PAGE = 10000;

    private final ResumeFileService resumeFileService;
    private final ResumeFileDtoMapper resumeFileDtoMapper;

    public ResumeHistoryController(
            ResumeFileService resumeFileService,
            ResumeFileDtoMapper resumeFileDtoMapper) {
        this.resumeFileService = resumeFileService;
        this.resumeFileDtoMapper = resumeFileDtoMapper;
    }

    /**
     * 查询简历历史列表。
     *
     * 接口契约：GET /api/resumes?page=&size=
     * 用户 ID 只从服务端安全上下文获取，不能由前端请求参数指定，
     * 从源头保证"历史列表只展示当前用户数据"。
     */
    @GetMapping
    public ApiResponse<ResumeFilePage> listResumeHistory(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        // 防御性收敛：页码限制在 1~MAX_PAGE（防大偏移扫描），每页条数限制在 1~50（契约已声明上限）
        int safePage = Math.max(1, Math.min(page, MAX_PAGE));
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        log.info("收到简历历史列表请求，userId={}, page={}, size={}", userId, safePage, safeSize);

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<com.smartview.resume.entity.ResumeFile> result =
                resumeFileService.pageUserResumeFiles(userId, safePage, safeSize);
        List<ResumeFile> items = resumeFileDtoMapper.toDtos(result.getRecords(), userId);

        ResumeFilePage pageData = new ResumeFilePage(items, safePage, safeSize, result.getTotal());
        log.info("简历历史列表返回，userId={}, total={}, returned={}", userId, result.getTotal(), items.size());
        return ApiResponse.success(pageData);
    }
}
