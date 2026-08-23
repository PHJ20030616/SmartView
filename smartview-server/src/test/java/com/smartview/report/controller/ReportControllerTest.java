package com.smartview.report.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.InterviewReport;
import com.smartview.generated.web.model.InterviewReportPage;
import com.smartview.generated.web.model.InterviewReportSummary;
import com.smartview.report.service.ReportQueryService;
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
 * 报告控制器单元测试。
 *
 * 通过 mockStatic 固定当前用户，验证控制器只做「取用户 + 委托服务 + 统一包装」，
 * 归属校验等业务逻辑已在 ReportQueryService 覆盖，此处不重复。
 */
@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    @Mock
    private ReportQueryService reportQueryService;

    private ReportController controller;

    @BeforeEach
    void setUp() {
        // @Mock 注入发生在测试实例创建之后，控制器必须在注入完成后构造，
        // 否则构造时拿到 null 服务引用。
        controller = new ReportController(reportQueryService);
    }

    @Test
    void listReports_取当前用户并委托分页查询() {
        InterviewReportSummary summary = new InterviewReportSummary(
                "88", "66", "7", InterviewReportSummary.StatusEnum.SUCCESS)
                .roleDirection(InterviewReportSummary.RoleDirectionEnum.JAVA_BACKEND)
                .overallScore(76);
        InterviewReportPage pageData = new InterviewReportPage(List.of(summary), 1, 10, 1L);
        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);
            when(reportQueryService.listReports(7L, 1, 10)).thenReturn(pageData);

            ApiResponse<InterviewReportPage> response = controller.listReports(1, 10);

            assertThat(response.getData()).isSameAs(pageData);
            assertThat(response.getData().getItems()).hasSize(1);
            assertThat(response.getData().getTotal()).isEqualTo(1L);
        }
    }

    @Test
    void listReports_分页参数越界时收敛到安全范围() {
        InterviewReportPage empty = new InterviewReportPage(List.of(), 1, 10, 0L);
        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);
            // size=100 越界 → 收敛到 50；page=0 越界 → 收敛到 1
            when(reportQueryService.listReports(7L, 1, 50)).thenReturn(empty);
            controller.listReports(0, 100);
            verify(reportQueryService).listReports(7L, 1, 50);
            // page=10001 越界 → 收敛到 MAX_PAGE=10000；size=0 越界 → 收敛到 1
            when(reportQueryService.listReports(7L, 10000, 1)).thenReturn(empty);
            controller.listReports(10001, 0);
            verify(reportQueryService).listReports(7L, 10000, 1);
        }
    }

    @Test
    void getReport_取当前用户并委托查询() {
        InterviewReport dto = new InterviewReport("88", "66", "7", InterviewReport.StatusEnum.SUCCESS);
        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);
            when(reportQueryService.getReport(7L, 88L)).thenReturn(dto);

            ApiResponse<InterviewReport> response = controller.getReport(88L);

            assertThat(response.getData()).isSameAs(dto);
            assertThat(response.getData().getId()).isEqualTo("88");
        }
    }

    @Test
    void retryReport_委托重试并返回报告() {
        InterviewReport dto = new InterviewReport("88", "66", "7", InterviewReport.StatusEnum.GENERATING);
        try (MockedStatic<SecurityContextHolder> security = mockStatic(SecurityContextHolder.class)) {
            security.when(SecurityContextHolder::getCurrentUserId).thenReturn(7L);
            when(reportQueryService.retryReport(7L, 88L)).thenReturn(dto);

            ApiResponse<InterviewReport> response = controller.retryReport(88L);

            assertThat(response.getData().getStatus())
                    .isEqualTo(InterviewReport.StatusEnum.GENERATING);
        }
    }
}
