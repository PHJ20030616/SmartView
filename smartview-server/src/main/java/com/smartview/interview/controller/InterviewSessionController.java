package com.smartview.interview.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.CreateInterviewSessionRequest;
import com.smartview.generated.web.model.InterviewSession;
import com.smartview.interview.service.InterviewSessionService;
import com.smartview.security.SecurityContextHolder;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面试会话控制器。
 *
 * 功能说明：
 * - POST /api/interview-sessions：创建面试会话，返回首题与进度范围
 * - GET /api/interview-sessions/{sessionId}：获取会话详情，页面刷新后恢复当前题目
 * - POST /api/interview-sessions/{sessionId}/finish：提前结束面试（转为 COMPLETED）
 *
 * 业务规则：
 * - 自动从安全上下文解析当前用户，用户只能操作自己的会话/画像
 * - 创建会话前置校验（确认简历 + 方向画像分析）在服务层完成，
 *   失败时返回 409/400 等业务错误
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-sessions")
public class InterviewSessionController {

    private final InterviewSessionService interviewSessionService;

    public InterviewSessionController(InterviewSessionService interviewSessionService) {
        this.interviewSessionService = interviewSessionService;
    }

    /**
     * 创建面试会话。
     *
     * 接口契约：POST /api/interview-sessions
     *
     * 创建成功后返回会话详情，含首题（currentQuestion）与进度范围
     * （expectedMinQuestions / expectedMaxQuestions）。响应由 ApiResponse
     * 统一包装（data 即会话数据），与契约 InterviewSessionResponse 一致。
     */
    @PostMapping
    public ApiResponse<InterviewSession> createSession(
            @Valid @RequestBody CreateInterviewSessionRequest request) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到创建面试会话请求，userId={}, profileId={}, direction={}",
                userId, request.getResumeProfileId(), request.getRoleDirection());
        InterviewSession response = interviewSessionService.createSession(userId, request);
        return ApiResponse.success(response);
    }

    /**
     * 获取面试会话详情。
     *
     * 接口契约：GET /api/interview-sessions/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public ApiResponse<InterviewSession> getSession(@PathVariable Long sessionId) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到面试会话详情查询请求，userId={}, sessionId={}", userId, sessionId);
        InterviewSession response = interviewSessionService.getSession(userId, sessionId);
        return ApiResponse.success(response);
    }

    /**
     * 提前结束面试。
     *
     * 接口契约：POST /api/interview-sessions/{sessionId}/finish
     * 仅 IN_PROGRESS 会话被转为 COMPLETED（endReason=USER_FINISHED_EARLY）；终态幂等返回现状。
     */
    @PostMapping("/{sessionId}/finish")
    public ApiResponse<InterviewSession> finishSession(@PathVariable Long sessionId) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到提前结束面试请求，userId={}, sessionId={}", userId, sessionId);
        InterviewSession response = interviewSessionService.finishSession(userId, sessionId);
        return ApiResponse.success(response);
    }
}
