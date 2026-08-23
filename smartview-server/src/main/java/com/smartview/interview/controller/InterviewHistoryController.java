package com.smartview.interview.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.InterviewSessionPage;
import com.smartview.interview.service.InterviewSessionService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面试会话历史控制器（Task 7.1）。
 *
 * 功能说明：
 * - GET /api/interview-sessions：分页查询当前登录用户的面试会话历史摘要
 * - 只返回当前用户数据；已软删除会话由 @TableLogic 在查询层自动过滤
 * - 摘要附带报告状态（reportId/reportStatus），前端据此提供"查看报告"入口；
 *   进行中的会话（IN_PROGRESS）可"继续面试"
 *
 * 设计取舍：
 * - 与 InterviewSessionController 共用 /api/interview-sessions 路径前缀：
 *   Spring MVC 按"方法+路径"精确匹配，GET 列表与其他方法（POST 创建、
 *   GET/{sessionId} 详情、POST/{sessionId}/finish 结束）互不冲突
 * - 分页参数做防御性收敛（page>=1、1<=size<=50），避免非法入参导致
 *   全表扫描或超大分页
 *
 * @author SmartView Team
 * @since 2026-08-17
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-sessions")
public class InterviewHistoryController {

    /** 每页最大条数，与契约 size.maximum=50 保持一致 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 页码上界：防止超大页码触发 MySQL 大偏移扫描（LIMIT offset 需遍历 offset 行） */
    private static final int MAX_PAGE = 10000;

    private final InterviewSessionService interviewSessionService;

    public InterviewHistoryController(InterviewSessionService interviewSessionService) {
        this.interviewSessionService = interviewSessionService;
    }

    /**
     * 查询面试会话历史列表。
     *
     * 接口契约：GET /api/interview-sessions?page=&size=
     * 用户 ID 只从服务端安全上下文获取，不能由前端请求参数指定，
     * 从源头保证"历史列表只展示当前用户数据"。
     */
    @GetMapping
    public ApiResponse<InterviewSessionPage> listInterviewSessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        // 防御性收敛：页码限制在 1~MAX_PAGE（防大偏移扫描），每页条数限制在 1~50（契约已声明上限）
        int safePage = Math.max(1, Math.min(page, MAX_PAGE));
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        log.info("收到面试会话历史列表请求，userId={}, page={}, size={}", userId, safePage, safeSize);

        InterviewSessionPage pageData = interviewSessionService.listSessions(userId, safePage, safeSize);
        return ApiResponse.success(pageData);
    }
}
