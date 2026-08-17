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
