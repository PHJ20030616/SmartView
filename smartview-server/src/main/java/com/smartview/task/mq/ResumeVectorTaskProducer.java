package com.smartview.task.mq;

import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 简历向量入库任务生产者。
 *
 * 生产者只发送画像 ID 和版本号，FastAPI 再从 MySQL 读取已确认画像。
 * 这样可以避免 MQ 中携带完整简历，也能让服务端掌握用户隔离条件的来源。
 */
@Slf4j
@Component
public class ResumeVectorTaskProducer {

    private final RabbitTemplate rabbitTemplate;

    public ResumeVectorTaskProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 带有限立即重试的发送方法，供确认后的首次投递使用。
     */
    public boolean sendWithRetry(
            ResumeVectorizeMessage message, int maxAttempts, long baseDelayMs) {
        if (message.getTraceId() == null) {
            message.setTraceId(TraceIdContext.currentTraceId());
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                        RabbitMQConfig.ROUTING_KEY_RESUME_VECTORIZE,
                        message);
                log.info("简历向量入库任务发送成功，taskId={}, profileId={}, version={}, attempt={}/{}",
                        message.getTaskId(),
                        message.getResumeProfileId(),
                        message.getProfileVersion(),
                        attempt,
                        maxAttempts);
                return true;
            } catch (AmqpException exception) {
                log.warn("简历向量入库任务发送失败，taskId={}, attempt={}/{}, error={}",
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
     * 定时补偿或手工重试使用的单次发送方法。
     */
    public void send(ResumeVectorizeMessage message) {
        try {
            if (message.getTraceId() == null) {
                message.setTraceId(TraceIdContext.currentTraceId());
            }
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                    RabbitMQConfig.ROUTING_KEY_RESUME_VECTORIZE,
                    message);
        } catch (AmqpException exception) {
            throw new BusinessException("向量入库任务投递失败：" + exception.getMessage());
        }
    }
}
