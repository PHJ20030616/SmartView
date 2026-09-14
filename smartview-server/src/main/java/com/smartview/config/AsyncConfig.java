package com.smartview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.smartview.common.api.TraceIdContext;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务执行器配置。
 *
 * 功能说明：
 * - 开启 @Async 支持，并提供候选池预生成的专用线程池
 * - 候选池是尽力而为的缓存：队列有界、拒绝策略为 CallerRunsPolicy，
 *   池饱和时由调用方线程执行（仍会完成，仅增加少量延迟），不丢任务
 * - 通过 TaskDecorator 把提交线程的 traceId 传入池线程并在任务结束后恢复：
 *   MDC 是线程私有的，默认不会跨线程传递。没有这个装饰器时，池线程会以空 MDC
 *   启动，首个任务里的 TraceIdContext.currentTraceId() 会把生成的 ID 永久写在该
 *   线程上，之后所有复用的任务都继承同一个 traceId（线上 74 个 AI 任务只有 11 个
 *   traceId、前 3 个覆盖 87% 即由此产生）。这里同时承担"传递"与"清理"两个职责。
 *
 * @author SmartView Team
 * @since 2026-08-07
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    /**
     * 候选池预生成线程池。
     *
     * @return 候选池专用 ThreadPoolTaskExecutor
     */
    @Bean("candidatePoolExecutor")
    public ThreadPoolTaskExecutor candidatePoolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("candidate-pool-");
        executor.setTaskDecorator(traceIdPropagatingDecorator());
        // 池与队列满载时由调用线程执行，保证候选池仍会尝试生成
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 把提交线程的 traceId 传递到池线程，并在任务结束后完整恢复池线程的 MDC。
     * <p>
     * 只读提交线程的 ID（{@link TraceIdContext#peekTraceId()}）而不做"生成"，
     * 避免在提交线程上凭空写入一个 traceId；池线程执行前一定先"设置或清空"，
     * 因此无论池线程此前残留什么，任务内看到的都是本次提交的链路。
     * </p>
     *
     * @return traceId 传递装饰器
     */
    private TaskDecorator traceIdPropagatingDecorator() {
        return runnable -> {
            // 装饰发生在提交线程上：此刻链路 ID 就是本次业务请求的 ID；
            // 只读不生成，避免在提交线程上凭空写入一个 traceId。
            String submitterTraceId = TraceIdContext.peekTraceId();
            return () -> {
                // Scope 在池线程上执行"设置或清空 + 结束时恢复"，因此无论池线程此前
                // 残留什么，任务内看到的都是本次提交的链路，任务结束后池线程现场复原。
                try (TraceIdContext.Scope ignored = TraceIdContext.scope(submitterTraceId)) {
                    runnable.run();
                }
            };
        };
    }
}
