package com.smartview.cleanup;

import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 清理任务生产者。
 *
 * <p>只发送清理目标（MinIO objectKey + 画像 ID 列表），FastAPI cleanup worker
 * 据此删除外部依赖数据；画像内容与用户隔离字段不进入消息，避免泄露与伪造。</p>
 */
@Slf4j
@Component
public class CleanupTaskProducer {

    private final RabbitTemplate rabbitTemplate;

    public CleanupTaskProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 带有限立即重试的发送方法，供软删除事务提交后的首次投递使用。
     *
     * @param maxAttempts 最大尝试次数（含首次）
     * @param baseDelayMs 指数退避基础延迟（毫秒）
     * @return true=发送成功；false=重试后仍失败（由调用方标记任务等待补偿调度）
     */
    public boolean sendWithRetry(CleanupMessage message, int maxAttempts, long baseDelayMs) {
        if (message.getTraceId() == null) {
            message.setTraceId(TraceIdContext.currentTraceId());
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                        RabbitMQConfig.ROUTING_KEY_CLEANUP,
                        message);
                log.info("清理任务发送成功，taskId={}, bizId={}, objectKey={}, attempt={}/{}",
                        message.getTaskId(), message.getBizId(), message.getObjectKey(),
                        attempt, maxAttempts);
                return true;
            } catch (AmqpException exception) {
                log.warn("清理任务发送失败，taskId={}, attempt={}/{}, error={}",
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
     * 定时补偿调度使用的单次发送方法；失败抛出异常由调度器记录并保持 RETRYING。
     */
    public void send(CleanupMessage message) {
        try {
            if (message.getTraceId() == null) {
                message.setTraceId(TraceIdContext.currentTraceId());
            }
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_SMARTVIEW_DIRECT,
                    RabbitMQConfig.ROUTING_KEY_CLEANUP,
                    message);
        } catch (AmqpException exception) {
            throw new BusinessException("清理任务投递失败：" + exception.getMessage());
        }
    }
}
