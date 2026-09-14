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
 * <p>
 * 线程模型约束（生产事故修复记录）：MDC 是<b>线程私有</b>的，而 Spring 的
 * HTTP 线程、MQ 监听线程、{@code @Async} 线程池线程、{@code @Scheduled} 调度线程
 * 都是长期存活的复用线程。因此<b>任何非 HTTP 入口都必须显式建立 traceId 作用域
 * 并在结束前恢复</b>，否则第一个任务写入的 ID 会被后续所有任务继承——线上曾出现
 * 74 个 AI 任务只落在 11 个 traceId 上（前 3 个覆盖 87%），导致按链路排查与
 * 成本归因完全失效。统一入口是 {@link #scope(String)}。
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
     * <p>
     * 注意：本方法会<b>写入 MDC</b>，因此只应在已建立作用域（HTTP 过滤器 /
     * {@link #scope(String)}）的线程上调用。在长期存活的线程池线程上裸调本方法，
     * 会把生成的 ID 永久留在该线程上并污染后续任务；需要"只看一眼"的场景请用
     * {@link #peekTraceId()}。
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
     * 只读读取当前线程的追踪 ID，<b>不生成、不写入 MDC</b>。
     *
     * @return 当前追踪 ID；从未设置时返回 null
     */
    public static String peekTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId == null || traceId.isBlank() ? null : traceId;
    }

    /**
     * 解析入参追踪 ID：非空则原样返回，为空则生成一个新的 UUID（不写入 MDC）。
     * <p>
     * 用于 MQ 结果消息等外部输入：契约要求消息必带 traceId，但仍需防御脏消息，
     * 保证每个消息处理都拿到一个可用 ID，而不是退化成"线程上残留的那个"。
     * </p>
     *
     * @param candidate 候选追踪 ID，可为 null 或空白
     * @return 可用的追踪 ID
     */
    public static String resolveTraceId(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return candidate;
    }

    /**
     * 生成一个新的追踪 ID（不写入 MDC），用于为一次调度/一轮扫描建立独立链路。
     *
     * @return 新生成的追踪 ID
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    /**
     * 建立追踪 ID 作用域：进入时写入指定 ID（为 null/空白则清空），退出时<b>恢复</b>
     * 进入前的值而不是直接清空，因此支持嵌套调用（例如 MQ 消费者内部再进入任务作用域）。
     * <p>
     * 必须使用 try-with-resources 保证异常路径也能恢复，否则线程池线程上的残留值
     * 会污染后续任务。
     * </p>
     *
     * <pre>{@code
     * try (TraceIdContext.Scope ignored = TraceIdContext.scope(message.getTraceId())) {
     *     // 本段代码内的日志与下游调用都归属该 traceId
     * }
     * }</pre>
     *
     * @param traceId 本作用域使用的追踪 ID
     * @return 可关闭的作用域句柄
     */
    public static Scope scope(String traceId) {
        return new Scope(traceId);
    }

    /**
     * 设置当前线程的追踪 ID
     * <p>
     * 通常在请求入口处调用，将客户端传递的追踪 ID 或新生成的追踪 ID 存储到 MDC 中。
     * 新代码请优先使用 {@link #scope(String)}，它能自动恢复现场，避免忘记清理。
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

    /**
     * 追踪 ID 作用域句柄，参见 {@link #scope(String)}。
     */
    public static final class Scope implements AutoCloseable {

        /** 进入作用域前该线程上的追踪 ID，用于退出时恢复 */
        private final String previousTraceId;

        private boolean closed;

        private Scope(String traceId) {
            this.previousTraceId = MDC.get(TRACE_ID_KEY);
            if (traceId == null || traceId.isBlank()) {
                MDC.remove(TRACE_ID_KEY);
            } else {
                MDC.put(TRACE_ID_KEY, traceId);
            }
        }

        @Override
        public void close() {
            // 幂等：重复 close 不能把现场改坏
            if (closed) {
                return;
            }
            closed = true;
            if (previousTraceId == null) {
                MDC.remove(TRACE_ID_KEY);
            } else {
                MDC.put(TRACE_ID_KEY, previousTraceId);
            }
        }
    }
}
