package com.smartview.task.mq;

import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import com.smartview.report.service.ReportTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 报告生成结果消费者。
 *
 * FastAPI 只通过结果消息回传报告内容与任务状态，Spring 负责将内容写回
 * interview_report / reference_answer，并推进会话与 ai_task 状态。
 * 业务校验失败（不可恢复）进入 DLQ；系统异常重试。
 */
@Slf4j
@Component
public class ReportResultConsumer {

    private final ReportTaskService reportTaskService;

    public ReportResultConsumer(ReportTaskService reportTaskService) {
        this.reportTaskService = reportTaskService;
    }

    /**
     * 消费报告生成结果；消息字段校验失败属于不可恢复业务错误，直接进入 DLQ。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_REPORT_GENERATE_RESULT)
    public void handleReportGenerateResult(@Payload ReportGenerateResultMessage message) {
        try {
            log.info("收到报告生成结果，taskId={}, sessionId={}, success={}",
                    message.getTaskId(), message.getSessionId(), message.getSuccess());
            reportTaskService.handleResult(message);
        } catch (BusinessException exception) {
            reportTaskService.markResultHandlingFailed(
                    message == null ? null : message.getTaskId(),
                    exception.getMessage());
            log.error("报告生成结果校验失败，消息进入死信队列，taskId={}, sessionId={}",
                    message == null ? null : message.getTaskId(),
                    message == null ? null : message.getSessionId(),
                    exception);
            throw new AmqpRejectAndDontRequeueException(
                    "报告生成结果业务校验失败，消息进入死信队列", exception);
        } catch (Exception exception) {
            // 数据库等基础设施异常应重新抛出，由 RabbitMQ 容器的有限重试策略处理。
            log.error("报告生成结果处理失败，等待 MQ 重试，taskId={}",
                    message == null ? null : message.getTaskId(),
                    exception);
            throw new RuntimeException("报告生成结果处理失败，等待重试", exception);
        }
    }
}
