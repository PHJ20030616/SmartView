package com.smartview.common.exception;

import com.smartview.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GlobalExceptionHandlerTest.TestExceptionController.class)
class GlobalExceptionHandlerTest {

    private static final String BUSINESS_TRACE_ID = "00000000-0000-4000-8000-000000000101";
    private static final String VALIDATION_TRACE_ID = "00000000-0000-4000-8000-000000000102";
    private static final String UNEXPECTED_TRACE_ID = "00000000-0000-4000-8000-000000000103";
    private static final String AUTH_TRACE_ID = "00000000-0000-4000-8000-000000000104";
    private static final String NOT_FOUND_TRACE_ID = "00000000-0000-4000-8000-000000000105";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void businessExceptionShouldReturnUnifiedResponse() throws Exception {
        mockMvc.perform(get("/test/exceptions/business").header("X-Trace-Id", BUSINESS_TRACE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_ERROR"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("业务规则不允许当前操作"))
                .andExpect(jsonPath("$.traceId").value(BUSINESS_TRACE_ID));
    }

    @Test
    @WithMockUser
    void validationExceptionShouldReturnChineseMessageAndTraceId() throws Exception {
        mockMvc.perform(post("/test/exceptions/validation")
                        .header("X-Trace-Id", VALIDATION_TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("参数校验失败：name 名称不能为空"))
                .andExpect(jsonPath("$.traceId").value(VALIDATION_TRACE_ID));
    }

    @Test
    @WithMockUser
    void unexpectedExceptionShouldReturnUnifiedResponse() throws Exception {
        mockMvc.perform(get("/test/exceptions/unexpected").header("X-Trace-Id", UNEXPECTED_TRACE_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("服务开小差了，请稍后再试"))
                .andExpect(jsonPath("$.traceId").value(UNEXPECTED_TRACE_ID));
    }

    @Test
    void unauthenticatedRequestShouldReturnUnifiedResponse() throws Exception {
        mockMvc.perform(get("/test/exceptions/business").header("X-Trace-Id", AUTH_TRACE_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("请先登录后再访问"))
                .andExpect(jsonPath("$.traceId").value(AUTH_TRACE_ID));
    }

    @Test
    void unmatchedRouteShouldReturnUnifiedNotFoundResponse() throws Exception {
        mockMvc.perform(get("/api/not-found").header("X-Trace-Id", NOT_FOUND_TRACE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"))
                .andExpect(jsonPath("$.traceId").value(NOT_FOUND_TRACE_ID));
    }

    @RestController
    @RequestMapping("/test/exceptions")
    static class TestExceptionController {

        @GetMapping("/business")
        ApiResponse<Void> business() {
            throw new BusinessException("业务规则不允许当前操作");
        }

        @PostMapping("/validation")
        ApiResponse<Void> validation(@Valid @RequestBody ValidationRequest request) {
            return ApiResponse.success(null);
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() {
            throw new IllegalStateException("test only");
        }
    }

    record ValidationRequest(@NotBlank(message = "名称不能为空") String name) {
    }
}
