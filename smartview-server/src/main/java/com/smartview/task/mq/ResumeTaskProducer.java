package com.smartview.task.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 简历解析任务 MQ 生产者
 *
 * 功能说明：
 * - 向 RabbitMQ 投递简历解析任务消息
 * - 支持立即重试机制（指数退避策略）
 * - 统一异常处理，转换为业务异常
 * - 自动注入 traceId，支持分布式链路追踪
 *
 * MQ 配置：
 * - Exchange：smartview.direct（直连交换机）
 * - Routing Key：resume.parse.task
 * - Queue：smartview.resume.parse
 *
 * 技术要点：
 * - 使用 Spring AMQP 发送消息
 * - 消息自动序列化为 JSON（RabbitMQ 默认使用 Jackson）
 * - 重试策略：指数退避（100ms -> 300ms -> 900ms）
 * - 失败不抛异常，返回 false 供调用方处理
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Slf4j
@Component
public class ResumeTaskProducer {

    /**
     * RabbitMQ 交换机名称
     */
    private static final String EXCHANGE = "smartview.direct";

    /**
     * 简历解析任务的路由键
     */
    private static final String ROUTING_KEY_RESUME_PARSE = "resume.parse.task";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数注入依赖
     *
     * @param rabbitTemplate RabbitMQ 模板
     * @param objectMapper   JSON 序列化器
     */
    public ResumeTaskProducer(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送简历解析任务到 MQ（带立即重试）
     * 使用指数退避策略：100ms -> 300ms -> 900ms
     *
     * @param message      解析任务消息
     * @param maxAttempts  最大重试次数（含首次发送）
     * @param baseDelayMs  基础延迟（毫秒），实际延迟 = baseDelayMs * 3^(attempt-1)
     * @return true=发送成功，false=重试后仍失败
     */
    public boolean sendResumeParseTaskWithRetry(ResumeParseMessage message, int maxAttempts, long baseDelayMs) {
        // 注入当前线程的 traceId
        if (message.getTraceId() == null) {
            message.setTraceId(TraceIdContext.currentTraceId());
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_RESUME_PARSE, message);
                log.info("简历解析任务发送成功，taskId={}, resumeFileId={}, attempt={}/{}",
                        message.getTaskId(), message.getResumeFileId(), attempt, maxAttempts);
                return true;

            } catch (AmqpException e) {
                log.warn("简历解析任务发送失败，taskId=, resumeFileId={}, attempt={}/{}, error={}",
                        message.getTaskId(), message.getResumeFileId(), attempt, maxAttempts, e.getMessage());

                // 未达最大重试次数，执行指数退避
                if (attempt < maxAttempts) {
                    long delay = (long) (baseDelayMs * Math.pow(3, attempt - 1));
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("重试等待被中断，taskId={}", message.getTaskId());
                        return false;
                    }
                }
            }
        }

        // 重试后仍失败
        log.error("简历解析任务发送最终失败，taskId={}, resumeFileId={}, maxAttempts={}",
                message.getTaskId(), message.getResumeFileId(), maxAttempts);
        return false;
    }

    /**
     * 发送简历解析任务到 MQ（单次发送，不重试）
     * 用于定时任务重新投递失败任务
     *
     * @param message 解析任务消息
     * @throws BusinessException MQ 发送失败时抛出
     */
    public void sendResumeParseTask(ResumeParseMessage message) {
        try {
            // 注入当前线程的 traceId
            if (message.getTraceId() == null) {
                message.setTraceId(TraceIdContext.currentTraceId());
            }

            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_RESUME_PARSE, message);
            log.info("简历解析任务发送成功，taskId={}, resumeFileId={}",
                    message.getTaskId(), message.getResumeFileId());

        } catch (AmqpException e) {
            log.error("简历解析任务发送失败，taskId={}, resumeFileId={}",
                    message.getTaskId(), message.getResumeFileId(), e);
            throw new BusinessException("消息队列投递失败：" + e.getMessage());
        }
    }
}
