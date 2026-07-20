package com.smartview.common.api;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 追踪 ID 过滤器
 * <p>
 * 拦截所有 HTTP 请求，在请求入口处设置追踪 ID，并在响应头中返回给客户端。
 * 追踪 ID 用于关联一次请求在整个系统中的调用链路，便于日志查询和问题排查。
 * </p>
 * <p>
 * 执行顺序：最高优先级（HIGHEST_PRECEDENCE），确保在其他过滤器之前执行。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * 过滤器核心逻辑
     * <p>
     * 1. 从请求头中解析或生成追踪 ID
     * 2. 将追踪 ID 设置到 MDC 上下文中
     * 3. 将追踪 ID 写入响应头
     * 4. 请求结束后清理 MDC 上下文
     * </p>
     *
     * @param request     HTTP 请求
     * @param response    HTTP 响应
     * @param filterChain 过滤器链
     * @throws ServletException Servlet 异常
     * @throws IOException      IO 异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        TraceIdContext.setTraceId(traceId);
        response.setHeader(TraceIdContext.TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIdContext.clear();
        }
    }

    /**
     * 解析或生成追踪 ID
     * <p>
     * 优先从请求头 X-Trace-Id 中获取客户端传递的追踪 ID。
     * 如果请求头中没有或格式非法（非 UUID 格式），则生成新的 UUID。
     * </p>
     * <p>
     * 注意：API 契约声明 traceId 为 UUID 格式，非法入站值不会继续传播，
     * 避免响应不符合契约规范。
     * </p>
     *
     * @param request HTTP 请求
     * @return 有效的追踪 ID（UUID 格式）
     */
    private String resolveTraceId(HttpServletRequest request) {
        String traceId = request.getHeader(TraceIdContext.TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            return UUID.randomUUID().toString();
        }

        String trimmedTraceId = traceId.trim();
        try {
            // API 契约声明 traceId 为 UUID；非法入站值不继续传播，避免响应不符合契约。
            return UUID.fromString(trimmedTraceId).toString();
        } catch (IllegalArgumentException exception) {
            return UUID.randomUUID().toString();
        }
    }
}
