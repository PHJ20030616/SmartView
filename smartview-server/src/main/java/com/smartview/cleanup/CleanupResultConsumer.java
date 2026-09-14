package com.smartview.cleanup;

import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 清理任务结果消费者。
 *
 * <p>FastAPI cleanup worker 通过结果消息回传清理状态，Spring 只负责将结果写回
 * ai_task（SUCCESS/FAILED + 结果载荷），不修改已软删除的业务行；因此
 * MinIO/Chroma 故障不会回滚 MySQL 中已经提交的删除状态。</p>
 */
@Slf4j
@Component
public class CleanupResultConsumer {

    private final CleanupTaskService cleanupTaskService;

    public CleanupResultConsumer(CleanupTaskService cleanupTaskService) {
        this.cleanupTaskService = cleanupTaskService;
    }

    /**
     * 消费清理结果；消息字段校验失败属于不可恢复业务错误，直接进入 DLQ。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_CLEANUP_RESULT)
    public void handleCleanupResult(@Payload CleanupResultMessage message) {
        // 监听线程长期存活：按消息建立 traceId 作用域，处理完自动恢复，
        // 避免首个消息的 traceId 被同线程后续消息继承。
        try (TraceIdContext.Scope ignored =
                     TraceIdContext.scope(message == null ? null : message.getTraceId())) {
            doHandleCleanupResult(message);
        }
    }

    private void doHandleCleanupResult(CleanupResultMessage message) {
        try {
            log.info("收到清理任务结果，taskId={}, bizId={}, success={}",
                    message == null ? null : message.getTaskId(),
                    message == null ? null : message.getBizId(),
                    message == null ? null : message.getSuccess());
            cleanupTaskService.handleResult(message);
        } catch (BusinessException exception) {
            cleanupTaskService.markResultHandlingFailed(
                    message == null ? null : message.getTaskId(),
                    exception.getMessage());
            log.error("清理结果业务校验失败，消息进入死信队列，taskId={}",
                    message == null ? null : message.getTaskId(), exception);
            throw new AmqpRejectAndDontRequeueException(
                    "清理结果业务校验失败，消息进入死信队列", exception);
        } catch (Exception exception) {
            // 数据库等基础设施异常应重新抛出，由 RabbitMQ 容器的有限重试策略处理
            log.error("清理结果处理失败，等待 MQ 重试，taskId={}",
                    message == null ? null : message.getTaskId(), exception);
            throw new RuntimeException("清理结果处理失败，等待重试", exception);
        }
    }
}
