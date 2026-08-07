package com.smartview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务执行器配置。
 *
 * 功能说明：
 * - 开启 @Async 支持，并提供候选池预生成的专用线程池
 * - 候选池是尽力而为的缓存：队列有界、拒绝策略为 CallerRunsPolicy，
 *   池饱和时由调用方线程执行（仍会完成，仅增加少量延迟），不丢任务
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
        // 池与队列满载时由调用线程执行，保证候选池仍会尝试生成
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
