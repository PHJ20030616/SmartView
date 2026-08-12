package com.smartview.task.mq;

import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 报告生成任务 MQ 生产者。
 *
 * 沿用画像分析生产者范式：向 smartview.direct 投递报告生成任务，
 * 支持带指数退避的有限立即重试；MQ 只在事务提交后发送（由 ReportTaskService 编排）。
 */
@Slf4j
@Component
public class ReportTaskProducer {

    private final RabbitTemplate rabbitTemplate;

    public ReportTaskProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 带有限立即重试的发送方法，供会话结束后的首次投递使用。
     */
    public boolean sendWithRetry(
            ReportGenerateMessage message, int maxAttempts, long baseDelayMs) {
        if (message.getTraceId() == null) {
            message.setTraceId(TraceIdContext.currentTraceId());
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                        RabbitMQConfig.ROUTING_KEY_REPORT_GENERATE,
                        message);
                log.info("报告生成任务发送成功，taskId={}, sessionId={}, attempt={}/{}",
                        message.getTaskId(), message.getSessionId(), attempt, maxAttempts);
                return true;
            } catch (AmqpException exception) {
                log.warn("报告生成任务发送失败，taskId={}, attempt={}/{}, error={}",
                        message.getTaskId(), attempt, maxAttempts, exception.getMessage());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(baseDelayMs * (long) Math.pow(3, attempt - 1));
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 单次发送方法，供后续补偿调度或手工重试使用；失败抛业务异常由调用方处理。
     */
    public void send(ReportGenerateMessage message) {
        try {
            if (message.getTraceId() == null) {
                message.setTraceId(TraceIdContext.currentTraceId());
            }
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                    RabbitMQConfig.ROUTING_KEY_REPORT_GENERATE,
                    message);
        } catch (AmqpException exception) {
            throw new BusinessException("报告生成任务投递失败：" + exception.getMessage());
        }
    }
}
