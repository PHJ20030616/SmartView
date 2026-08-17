package com.smartview.report.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.InterviewReport;
import com.smartview.report.service.ReportQueryService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话报告控制器。
 *
 * 接口契约：GET /api/interview-sessions/{sessionId}/report。
 * 面试结束页按 sessionId 进入报告页时调用；会话归属校验在
 * ReportQueryService 完成（会话必须属于当前登录用户）。
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-sessions")
public class SessionReportController {

    private final ReportQueryService reportQueryService;

    public SessionReportController(ReportQueryService reportQueryService) {
        this.reportQueryService = reportQueryService;
    }

    /**
     * 按会话 ID 查询面试报告。
     *
     * 接口契约：GET /api/interview-sessions/{sessionId}/report
     */
    @GetMapping("/{sessionId}/report")
    public ApiResponse<InterviewReport> getReportBySession(@PathVariable Long sessionId) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到会话报告查询请求，userId={}, sessionId={}", userId, sessionId);
        return ApiResponse.success(reportQueryService.getReportBySession(userId, sessionId));
    }
}
