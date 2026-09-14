package com.smartview.common.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import com.smartview.config.AsyncConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链路追踪上下文的作用域语义测试。
 *
 * <p>背景（线上生产事故）：{@code TraceIdContext.currentTraceId()} 在 MDC 为空时会把
 * 新生成的 ID 写回 MDC，而 MQ 监听线程、{@code @Async} 线程池线程、{@code @Scheduled}
 * 调度线程都是长期复用的。只要有一处入口没有显式建立作用域，第一个任务的 traceId 就
 * 会被后面所有任务继承——线上 74 个 AI 任务仅落在 11 个 traceId 上、前 3 个覆盖 87%
 * 即由此产生，按链路排查与按会话归因成本全部失效。</p>
 *
 * <p>本测试固定住修复后的三条不变量：</p>
 * <ol>
 *   <li>作用域退出后必须恢复进入前的值，而不是留下本次的值（否则线程被污染）；</li>
 *   <li>作用域可嵌套，内层退出后外层仍然有效；</li>
 *   <li>{@code @Async} 线程池必须把提交线程的 traceId 传进池线程，并在任务结束后复原。</li>
 * </ol>
 */
class TraceIdContextTest {

    private static final String FIRST = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND = "22222222-2222-2222-2222-222222222222";

    @AfterEach
    void tearDown() {
        // MDC 是线程私有的，但测试线程会被复用；不清理会污染同 JVM 的后续用例
        TraceIdContext.clear();
    }

    @Test
    void scopeRestoresPreviousTraceIdOnExit() {
        TraceIdContext.setTraceId(FIRST);

        try (TraceIdContext.Scope ignored = TraceIdContext.scope(SECOND)) {
            assertThat(TraceIdContext.peekTraceId()).isEqualTo(SECOND);
        }

        // 关键断言：退出作用域后回到 FIRST，而不是把 SECOND 留在该线程上
        assertThat(TraceIdContext.peekTraceId()).isEqualTo(FIRST);
    }

    @Test
    void scopeWithBlankTraceIdClearsInsideAndRestoresOutside() {
        TraceIdContext.setTraceId(FIRST);

        try (TraceIdContext.Scope ignored = TraceIdContext.scope(null)) {
            assertThat(TraceIdContext.peekTraceId()).isNull();
        }

        assertThat(TraceIdContext.peekTraceId()).isEqualTo(FIRST);
    }

    @Test
    void nestedScopesRestoreInOrder() {
        TraceIdContext.setTraceId(FIRST);

        try (TraceIdContext.Scope outer = TraceIdContext.scope(SECOND)) {
            assertThat(TraceIdContext.peekTraceId()).isEqualTo(SECOND);
            try (TraceIdContext.Scope inner = TraceIdContext.scope("33333333-3333-3333-3333-333333333333")) {
                assertThat(TraceIdContext.peekTraceId())
                        .isEqualTo("33333333-3333-3333-3333-333333333333");
            }
            // 内层退出后必须回到外层（MQ 消费者内再进入任务作用域就是这种形状）
            assertThat(TraceIdContext.peekTraceId()).isEqualTo(SECOND);
        }

        assertThat(TraceIdContext.peekTraceId()).isEqualTo(FIRST);
    }

    @Test
    void peekTraceIdNeverGeneratesNorWrites() {
        assertThat(TraceIdContext.peekTraceId()).isNull();
        // 只读语义：连续读取不会凭空造出一个 ID，也不会把它留在线程上
        assertThat(TraceIdContext.peekTraceId()).isNull();
        assertThat(TraceIdContext.currentTraceId()).isNotBlank();
    }

    @Test
    void resolveTraceIdFallsBackToFreshIdForBlankInput() {
        assertThat(TraceIdContext.resolveTraceId(null)).isNotBlank();
        assertThat(TraceIdContext.resolveTraceId("   ")).isNotBlank();
        assertThat(TraceIdContext.resolveTraceId(FIRST)).isEqualTo(FIRST);
        // 兜底 ID 不写入 MDC：调用方必须显式建立作用域才能生效
        assertThat(TraceIdContext.peekTraceId()).isNull();
    }

    @Test
    void sequentialTasksOnOnePoolThreadDoNotShareTraceId() throws Exception {
        // 复现并锁定修复：同一池线程先后处理两个任务（消息），
        // 各自必须看到自己的 traceId，而不是第一个任务留下的那个。
        ExecutorService singleThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Future<String> firstTask = singleThread.submit(() -> {
                try (TraceIdContext.Scope ignored = TraceIdContext.scope(FIRST)) {
                    return TraceIdContext.peekTraceId();
                }
            });
            Future<String> secondTask = singleThread.submit(() -> {
                try (TraceIdContext.Scope ignored = TraceIdContext.scope(SECOND)) {
                    return TraceIdContext.peekTraceId();
                }
            });

            assertThat(firstTask.get()).isEqualTo(FIRST);
            assertThat(secondTask.get()).isEqualTo(SECOND);

            // 任务之间线程上不应残留上一个任务的 ID
            Future<String> betweenTasks = singleThread.submit(TraceIdContext::peekTraceId);
            assertThat(betweenTasks.get()).isNull();
        } finally {
            singleThread.shutdownNow();
        }
    }

    @Test
    void asyncExecutorPropagatesSubmitterTraceId() throws Exception {
        // @Async 线程池默认不传递 MDC；没有 TaskDecorator 时池线程以空 MDC 启动，
        // 任务内的 currentTraceId() 会把生成的 ID 永久写在该线程上。
        ThreadPoolTaskExecutor executor = new AsyncConfig().candidatePoolExecutor();
        try {
            TraceIdContext.setTraceId(FIRST);
            Future<String> inherited = executor.submit(TraceIdContext::peekTraceId);

            assertThat(inherited.get()).isEqualTo(FIRST);
        } finally {
            executor.shutdown();
        }
    }
}
