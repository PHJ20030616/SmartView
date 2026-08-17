package com.smartview.report.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.InterviewReport;
import com.smartview.report.service.ReportQueryService;
import com.smartview.security.SecurityContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 会话报告控制器单元测试。
 *
 * 验证 GET /api/interview-sessions/{sessionId}/report 路径：取当前用户、
 * 按会话委托查询并统一包装；会话归属校验在 ReportQueryService 完成。
 */
@ExtendWith(MockitoExtension.class)
class SessionReportControllerTest {

    @Mock
    private ReportQueryService reportQueryService;

    private SessionReportController controller;

    @BeforeEach
    void setUp() {
        // @Mock 注入发生在测试实例创建之后，控制器必须在注入完成后构造，
        // 否则构造时拿到 null 服务引用。
        controller = new SessionReportController(reportQueryService);
    }

    @Test
    void getReportBySession_取当前用户并按会话委托查询() {
        InterviewReport dto = new InterviewReport("88", "66", "7", InterviewReport.StatusEnum.SUCCESS);
        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);
            when(reportQueryService.getReportBySession(7L, 66L)).thenReturn(dto);

            ApiResponse<InterviewReport> response = controller.getReportBySession(66L);

            assertThat(response.getData()).isSameAs(dto);
            assertThat(response.getData().getSessionId()).isEqualTo("66");
        }
    }
}
