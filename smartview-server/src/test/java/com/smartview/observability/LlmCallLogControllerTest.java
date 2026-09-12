package com.smartview.observability;

import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.LlmCallPage;
import com.smartview.generated.web.model.LlmCallStats;
import com.smartview.observability.config.LlmCallLogProperties;
import com.smartview.observability.controller.LlmCallLogController;
import com.smartview.observability.service.LlmCallLogQueryService;
import com.smartview.security.SecurityContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 调用观测控制器测试。
 *
 * 两件事必须守住：
 * 1. **越权拦截发生在查询之前**——llm_call_log 是跨用户视图，未授权请求不得触碰查询逻辑；
 * 2. 分页参数按契约收敛（size.maximum=50），不把越界值透传给数据库。
 *
 * 响应 JSON 结构用 MockMvc 的 standaloneSetup 断言：本仓库已有 MockMvc 依赖，
 * 而 standalone 用法无需启动 Spring 上下文，是最低成本的"字段名真的符合契约"验证。
 */
@ExtendWith(MockitoExtension.class)
class LlmCallLogControllerTest {

    @Mock
    private LlmCallLogQueryService queryService;

    private LlmCallLogProperties properties;
    private LlmCallLogController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new LlmCallLogProperties();
        properties.setOperatorUsernames(List.of("ops"));
        controller = new LlmCallLogController(queryService, properties);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void rejectsUserOutsideWhitelistBeforeQuerying() {
        try (MockedStatic<SecurityContextHolder> holder =
                     mockStatic(SecurityContextHolder.class)) {
            holder.when(SecurityContextHolder::getCurrentUsername).thenReturn("normal-user");

            assertThatThrownBy(() -> controller.listLlmCalls(null, null, null, null, 1, 20))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无权访问");
        }

        // 越权请求不得触碰查询逻辑
        verify(queryService, never()).listCalls(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void emptyWhitelistDeniesEvenAuthenticatedUser() {
        // 默认配置（未设置白名单）必须关闭接口，避免漏配环境把运维数据暴露给登录用户
        LlmCallLogController closedController =
                new LlmCallLogController(queryService, new LlmCallLogProperties());

        try (MockedStatic<SecurityContextHolder> holder =
                     mockStatic(SecurityContextHolder.class)) {
            holder.when(SecurityContextHolder::getCurrentUsername).thenReturn("ops");

            assertThatThrownBy(() -> closedController.listLlmCalls(null, null, null, null, 1, 20))
                    .isInstanceOf(BusinessException.class);
        }

        verify(queryService, never()).listCalls(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void operatorReceivesContractShapedResponse() throws Exception {
        LlmCallStats stats = new LlmCallStats();
        stats.setTotalCalls(3L);
        stats.setSuccessCalls(3L);
        stats.setSuccessRate(1.0d);
        stats.setP95LatencyMs(1200);
        stats.setTotalTokens(900L);

        LlmCallPage page = new LlmCallPage();
        page.setItems(List.of());
        page.setPage(1);
        page.setSize(20);
        page.setTotal(3L);
        page.setStats(stats);

        when(queryService.listCalls(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(page);

        try (MockedStatic<SecurityContextHolder> holder =
                     mockStatic(SecurityContextHolder.class)) {
            holder.when(SecurityContextHolder::getCurrentUsername).thenReturn("ops");

            mockMvc.perform(get("/api/llm-calls").param("page", "1").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.page").value(1))
                    .andExpect(jsonPath("$.data.total").value(3))
                    .andExpect(jsonPath("$.data.stats.p95LatencyMs").value(1200));
        }
    }

    @Test
    void pageSizeIsCappedAtContractMaximum() {
        LlmCallPage page = new LlmCallPage();
        page.setItems(List.of());
        page.setPage(1);
        page.setSize(50);
        page.setTotal(0L);
        page.setStats(new LlmCallStats());
        when(queryService.listCalls(any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(page);

        try (MockedStatic<SecurityContextHolder> holder =
                     mockStatic(SecurityContextHolder.class)) {
            holder.when(SecurityContextHolder::getCurrentUsername).thenReturn("ops");

            controller.listLlmCalls(null, null, null, null, 0, 999);
        }

        // 契约声明 size.maximum=50，控制器必须收敛而不是把 999 透传给数据库；
        // page=0 同样收敛到 1
        verify(queryService).listCalls(any(), any(), any(), any(), eq(1), eq(50));
    }
}
