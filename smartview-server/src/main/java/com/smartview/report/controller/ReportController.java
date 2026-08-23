package com.smartview.report.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.InterviewReport;
import com.smartview.generated.web.model.InterviewReportPage;
import com.smartview.report.service.ReportQueryService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 报告控制器（列表查询 / 按报告 ID 查询 / 失败重试）。
 *
 * 接口契约：GET /api/reports、GET /api/reports/{reportId}、POST /api/reports/{reportId}/retry。
 * 归属校验在 ReportQueryService 完成（报告必须属于当前登录用户），
 * 控制器只负责解析用户、委托查询与统一包装响应。
 */
@Slf4j
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    /** 每页最大条数，与契约 size.maximum=50 保持一致 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 页码上界：防止超大页码触发 MySQL 大偏移扫描（LIMIT offset 需遍历 offset 行） */
    private static final int MAX_PAGE = 10000;

    private final ReportQueryService reportQueryService;

    public ReportController(ReportQueryService reportQueryService) {
        this.reportQueryService = reportQueryService;
    }

    /**
     * 查询当前用户的报告历史列表（分页，按创建时间倒序）。
     *
     * 接口契约：GET /api/reports?page=&size=
     * 用户 ID 只从服务端安全上下文获取，不能由前端请求参数指定，
     * 从源头保证"报告列表只展示当前用户数据"。
     * 生成中/成功/失败的报告均返回，页面进入详情后自会轮询或重试。
     */
    @GetMapping
    public ApiResponse<InterviewReportPage> listReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        // 防御性收敛：页码限制在 1~MAX_PAGE（防大偏移扫描），每页条数限制在 1~50（契约已声明上限）
        int safePage = Math.max(1, Math.min(page, MAX_PAGE));
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        log.info("收到报告历史列表请求，userId={}, page={}, size={}", userId, safePage, safeSize);

        InterviewReportPage pageData = reportQueryService.listReports(userId, safePage, safeSize);
        return ApiResponse.success(pageData);
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
