package com.smartview.common.api;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;

/**
 * 分布式追踪 ID 上下文管理器
 * <p>
 * 使用 SLF4J MDC (Mapped Diagnostic Context) 管理请求的追踪 ID。
 * 追踪 ID 在整个请求生命周期中保持不变，用于关联日志和跨服务调用链路。
 * </p>
 */
public final class TraceIdContext {

    /** MDC 中存储追踪 ID 的键名 */
    public static final String TRACE_ID_KEY = "traceId";

    /** HTTP 请求头中传递追踪 ID 的字段名 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * 私有构造函数，防止实例化
     */
    private TraceIdContext() {
    }

    /**
     * 获取当前线程的追踪 ID
     * <p>
     * 如果当前线程尚未设置追踪 ID，则自动生成一个新的 UUID 并存储到 MDC 中。
     * </p>
     *
     * @return 当前线程的追踪 ID
     */
    public static String currentTraceId() {
        return Optional.ofNullable(MDC.get(TRACE_ID_KEY))
                .filter(traceId -> !traceId.isBlank())
                .orElseGet(() -> {
                    String traceId = UUID.randomUUID().toString();
                    MDC.put(TRACE_ID_KEY, traceId);
                    return traceId;
                });
    }

    /**
     * 设置当前线程的追踪 ID
     * <p>
     * 通常在请求入口处调用，将客户端传递的追踪 ID 或新生成的追踪 ID 存储到 MDC 中。
     * </p>
     *
     * @param traceId 追踪 ID
     */
    public static void setTraceId(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /**
     * 清除当前线程的追踪 ID
     * <p>
     * 通常在请求结束时调用，避免在线程池复用场景下追踪 ID 污染。
     * </p>
     */
    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }
}
