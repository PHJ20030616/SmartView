package com.smartview.task.mq;

import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import com.smartview.resume.service.ResumeVectorizationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 简历向量入库结果消费者。
 *
 * FastAPI 只通过结果消息回传任务状态，Spring 负责将结果写回 ai_task。
 * 画像的 CONFIRMED 状态不由此消费者修改，因此 Chroma/Redis 故障不会回滚权威画像。
 */
@Slf4j
@Component
public class ResumeVectorizeResultConsumer {

    private final ResumeVectorizationService resumeVectorizationService;

    public ResumeVectorizeResultConsumer(ResumeVectorizationService resumeVectorizationService) {
        this.resumeVectorizationService = resumeVectorizationService;
    }

    /**
     * 消费向量入库结果；消息字段校验失败属于不可恢复业务错误，直接进入 DLQ。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_RESUME_VECTORIZE_RESULT)
    public void handleResumeVectorizeResult(@Payload ResumeVectorizeResultMessage message) {
        try {
            log.info("收到简历向量入库结果，taskId={}, profileId={}, version={}, success={}",
                    message.getTaskId(),
                    message.getResumeProfileId(),
                    message.getProfileVersion(),
                    message.getSuccess());
            resumeVectorizationService.handleResult(message);
        } catch (BusinessException exception) {
            resumeVectorizationService.markResultHandlingFailed(
                    message == null ? null : message.getTaskId(),
                    exception.getMessage());
            log.error("简历向量入库结果校验失败，消息进入死信队列，taskId={}, profileId={}",
                    message == null ? null : message.getTaskId(),
                    message == null ? null : message.getResumeProfileId(),
                    exception);
            throw new AmqpRejectAndDontRequeueException(
                    "简历向量入库结果业务校验失败，消息进入死信队列", exception);
        } catch (Exception exception) {
            // 数据库等基础设施异常应重新抛出，由 RabbitMQ 容器的有限重试策略处理。
            log.error("简历向量入库结果处理失败，等待 MQ 重试，taskId={}",
                    message == null ? null : message.getTaskId(),
                    exception);
            throw new RuntimeException("简历向量入库结果处理失败，等待重试", exception);
        }
    }
}
