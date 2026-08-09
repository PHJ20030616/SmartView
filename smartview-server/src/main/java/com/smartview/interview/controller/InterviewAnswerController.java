package com.smartview.interview.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.SubmitAnswerData;
import com.smartview.generated.web.model.SubmitAnswerRequest;
import com.smartview.interview.service.InterviewAnswerService;
import com.smartview.security.SecurityContextHolder;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回答提交控制器。
 *
 * 功能说明：
 * - POST /api/interview-sessions/{sessionId}/answers：提交当前题目回答，推进面试
 *
 * 业务规则：
 * - 自动从安全上下文解析当前用户，用户只能操作自己的会话
 * - 幂等：同一 request_id 重复提交返回既有结果（policy 4.1）；
 *   过期题目 / 乐观锁冲突返回 409（policy 4.3）
 *
 * @author SmartView Team
 * @since 2026-08-09
 */
@Slf4j
@RestController
@RequestMapping("/api/interview-sessions")
public class InterviewAnswerController {

    private final InterviewAnswerService interviewAnswerService;

    public InterviewAnswerController(InterviewAnswerService interviewAnswerService) {
        this.interviewAnswerService = interviewAnswerService;
    }

    /**
     * 提交回答。
     *
     * 接口契约：POST /api/interview-sessions/{sessionId}/answers
     * 返回回答 ID、评估、下一题（结束为空）与会话状态，响应由 ApiResponse 统一包装。
     */
    @PostMapping("/{sessionId}/answers")
    public ApiResponse<SubmitAnswerData> submitAnswer(
            @PathVariable Long sessionId,
            @Valid @RequestBody SubmitAnswerRequest request) {
        Long userId = SecurityContextHolder.getCurrentUserId();
        log.info("收到回答提交请求，userId={}, sessionId={}, questionId={}",
                userId, sessionId, request.getQuestionId());
        SubmitAnswerData response = interviewAnswerService.submitAnswer(userId, sessionId, request);
        return ApiResponse.success(response);
    }
}
