package com.smartview.observability.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.LlmCallPage;
import com.smartview.observability.config.LlmCallLogProperties;
import com.smartview.observability.service.LlmCallLogQueryService;
import com.smartview.security.SecurityContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * LLM 调用观测控制器（只读）。
 *
 * 接口契约：GET /api/llm-calls。
 * 这是跨用户的运维视图，访问控制见 {@link #requireOperator()}。
 */
@Slf4j
@RestController
@RequestMapping("/api/llm-calls")
public class LlmCallLogController {

    /** 每页最大条数，与契约 size.maximum=50 保持一致 */
    private static final int MAX_PAGE_SIZE = 50;
    /** 页码上界：防止超大页码触发 MySQL 大偏移扫描 */
    private static final int MAX_PAGE = 10000;

    private final LlmCallLogQueryService queryService;
    private final LlmCallLogProperties properties;

    public LlmCallLogController(LlmCallLogQueryService queryService,
                                LlmCallLogProperties properties) {
        this.queryService = queryService;
        this.properties = properties;
    }

    /**
     * 分页查询 LLM 调用记录。
     *
     * 接口契约：GET /api/llm-calls?scene=&promptVersion=&from=&to=&page=&size=
     */
    @GetMapping
    public ApiResponse<LlmCallPage> listLlmCalls(
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String promptVersion,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireOperator();

        // 防御性收敛：页码限制在 1~MAX_PAGE（防大偏移扫描），每页条数限制在 1~50（契约已声明上限）
        int safePage = Math.max(1, Math.min(page, MAX_PAGE));
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        log.info("收到 LLM 调用记录查询请求，scene={}, promptVersion={}, page={}, size={}",
                scene, promptVersion, safePage, safeSize);

        return ApiResponse.success(
                queryService.listCalls(scene, promptVersion, from, to, safePage, safeSize));
    }

    /**
     * 运维白名单校验。
     *
     * llm_call_log 表没有 user_id 维度，接口返回的是**全体用户**的调用元数据
     * （场景、模型、耗时、token、链路 ID）。若对所有登录用户开放，等于把运维信息
     * 暴露给全部用户。plan_1.1 不做多租户 RBAC，因此这里用配置白名单做最小可行的控制，
     * 且白名单为空即为关闭——默认安全，而不是默认开放。
     */
    private void requireOperator() {
        String username = SecurityContextHolder.getCurrentUsername();
        if (!properties.isOperator(username)) {
            log.warn("非白名单用户尝试访问 LLM 调用观测接口，username={}", username);
            throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问 LLM 调用观测数据");
        }
    }
}
