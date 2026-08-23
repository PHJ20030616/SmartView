package com.smartview.interview.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.InterviewSessionPage;
import com.smartview.generated.web.model.InterviewSessionSummary;
import com.smartview.interview.service.InterviewSessionService;
import com.smartview.security.SecurityContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试会话历史控制器单元测试（Task 7.1）。
 *
 * 验证 GET /api/interview-sessions 路径：取当前登录用户、按分页委托查询并统一包装；
 * 分页参数越界时收敛到合法范围。
 */
@ExtendWith(MockitoExtension.class)
class InterviewHistoryControllerTest {

    @Mock
    private InterviewSessionService interviewSessionService;

    private InterviewHistoryController controller;

    @BeforeEach
    void setUp() {
        // @Mock 注入发生在测试实例创建之后，控制器必须在注入完成后构造，
        // 否则构造时拿到 null 服务引用。
        controller = new InterviewHistoryController(interviewSessionService);
    }

    @Test
    void listInterviewSessions_取当前用户并按分页委托查询() {
        InterviewSessionSummary summary = new InterviewSessionSummary(
                "66", "7", "10",
                InterviewSessionSummary.RoleDirectionEnum.JAVA_BACKEND,
                InterviewSessionSummary.StatusEnum.COMPLETED)
                .questionCount(8)
                .reportId("500")
                .reportStatus(InterviewSessionSummary.ReportStatusEnum.SUCCESS);
        InterviewSessionPage pageData = new InterviewSessionPage(List.of(summary), 1, 10, 1L);
        when(interviewSessionService.listSessions(7L, 1, 10)).thenReturn(pageData);

        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);

            ApiResponse<InterviewSessionPage> response = controller.listInterviewSessions(1, 10);

            // 用户 ID 只来自安全上下文，前端无法指定他人数据
            verify(interviewSessionService).listSessions(7L, 1, 10);
            assertThat(response.getData().getItems()).hasSize(1);
            assertThat(response.getData().getItems().get(0).getReportStatus())
                    .isEqualTo(InterviewSessionSummary.ReportStatusEnum.SUCCESS);
            assertThat(response.getData().getTotal()).isEqualTo(1);
        }
    }

    @Test
    void listInterviewSessions_分页参数越界时收敛为合法范围() {
        // 页码 0 → 1；每页 999 → 上限 50（与契约 maximum=50 一致）
        when(interviewSessionService.listSessions(7L, 1, 50))
                .thenReturn(new InterviewSessionPage(List.of(), 1, 50, 0L));

        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);

            controller.listInterviewSessions(0, 999);

            verify(interviewSessionService).listSessions(7L, 1, 50);
        }
    }
}
