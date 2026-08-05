package com.smartview.task.mq;

import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 画像分析任务生产者。
 *
 * 生产者只发送画像 ID、版本号和面试方向，FastAPI 再从 MySQL 读取已确认画像，
 * 从 Chroma 检索简历切片与知识/面经材料。这样可以避免 MQ 中携带完整简历，
 * 也能让服务端掌握用户隔离条件的来源。
 */
@Slf4j
@Component
public class ProfileAnalyzeTaskProducer {

    private final RabbitTemplate rabbitTemplate;

    public ProfileAnalyzeTaskProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 带有限立即重试的发送方法，供方向选择后的首次投递使用。
     */
    public boolean sendWithRetry(
            ProfileAnalyzeMessage message, int maxAttempts, long baseDelayMs) {
        if (message.getTraceId() == null) {
            message.setTraceId(TraceIdContext.currentTraceId());
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                        RabbitMQConfig.ROUTING_KEY_PROFILE_ANALYZE,
                        message);
                log.info("画像分析任务发送成功，taskId={}, profileId={}, version={}, direction={}, attempt={}/{}",
                        message.getTaskId(),
                        message.getResumeProfileId(),
                        message.getProfileVersion(),
                        message.getRoleDirection(),
                        attempt,
                        maxAttempts);
                return true;
            } catch (AmqpException exception) {
                log.warn("画像分析任务发送失败，taskId={}, attempt={}/{}, error={}",
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
    public void send(ProfileAnalyzeMessage message) {
        try {
            if (message.getTraceId() == null) {
                message.setTraceId(TraceIdContext.currentTraceId());
            }
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                    RabbitMQConfig.ROUTING_KEY_PROFILE_ANALYZE,
                    message);
        } catch (AmqpException exception) {
            throw new BusinessException("画像分析任务投递失败：" + exception.getMessage());
        }
    }
}
