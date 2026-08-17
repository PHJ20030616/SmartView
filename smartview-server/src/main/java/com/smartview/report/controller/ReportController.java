package com.smartview.report.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.InterviewReport;
import com.smartview.report.service.ReportQueryService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告控制器（按报告 ID 查询 / 失败重试）。
 *
 * 接口契约：GET /api/reports/{reportId}、POST /api/reports/{reportId}/retry。
 * 归属校验在 ReportQueryService 完成（报告必须属于当前登录用户），
 * 控制器只负责解析用户、委托查询与统一包装响应。
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportQueryService reportQueryService;

    public ReportController(ReportQueryService reportQueryService) {
        this.reportQueryService = reportQueryService;
    }

    /**
     * 按报告 ID 查询报告详情。
     *
     * 接口契约：GET /api/reports/{reportId}
     */
    @GetMapping("/{reportId}")
    public ApiResponse<InterviewReport> getReport(@PathVariable Long reportId) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到报告详情查询请求，userId={}, reportId={}", userId, reportId);
        return ApiResponse.success(reportQueryService.getReport(userId, reportId));
    }

    /**
     * 报告失败后重试生成。
     *
     * 接口契约：POST /api/reports/{reportId}/retry
     * 仅 FAILED 报告真正重建任务；GENERATING/SUCCESS 幂等返回现状（服务层保证）。
     */
    @PostMapping("/{reportId}/retry")
    public ApiResponse<InterviewReport> retryReport(@PathVariable Long reportId) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到报告重试请求，userId={}, reportId={}", userId, reportId);
        return ApiResponse.success(reportQueryService.retryReport(userId, reportId));
    }
}
