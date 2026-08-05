package com.smartview.task.mq;

import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import com.smartview.resume.service.ProfileAnalysisTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 画像分析结果消费者。
 *
 * FastAPI 只通过结果消息回传任务状态，Spring 负责将结果写回 ai_task，
 * 并在成功时将分析内容写入 profile_analysis 表。
 */
@Slf4j
@Component
public class ProfileAnalyzeResultConsumer {

    private final ProfileAnalysisTaskService profileAnalysisTaskService;

    public ProfileAnalyzeResultConsumer(ProfileAnalysisTaskService profileAnalysisTaskService) {
        this.profileAnalysisTaskService = profileAnalysisTaskService;
    }

    /**
     * 消费画像分析结果；消息字段校验失败属于不可恢复业务错误，直接进入 DLQ。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_PROFILE_ANALYZE_RESULT)
    public void handleProfileAnalyzeResult(@Payload ProfileAnalyzeResultMessage message) {
        try {
            log.info("收到画像分析结果，taskId={}, profileId={}, version={}, direction={}, success={}",
                    message.getTaskId(),
                    message.getResumeProfileId(),
                    message.getProfileVersion(),
                    message.getRoleDirection(),
                    message.getSuccess());
            profileAnalysisTaskService.handleResult(message);
        } catch (BusinessException exception) {
            profileAnalysisTaskService.markResultHandlingFailed(
                    message == null ? null : message.getTaskId(),
                    exception.getMessage());
            log.error("画像分析结果校验失败，消息进入死信队列，taskId={}, profileId={}",
                    message == null ? null : message.getTaskId(),
                    message == null ? null : message.getResumeProfileId(),
                    exception);
            throw new AmqpRejectAndDontRequeueException(
                    "画像分析结果业务校验失败，消息进入死信队列", exception);
        } catch (Exception exception) {
            // 数据库等基础设施异常应重新抛出，由 RabbitMQ 容器的有限重试策略处理。
            log.error("画像分析结果处理失败，等待 MQ 重试，taskId={}",
                    message == null ? null : message.getTaskId(),
                    exception);
            throw new RuntimeException("画像分析结果处理失败，等待重试", exception);
        }
    }
}
