package com.smartview.task.mq;

import com.smartview.common.api.TraceIdContext;
import com.smartview.common.exception.BusinessException;
import com.smartview.config.RabbitMQConfig;
import com.smartview.resume.service.ResumeProfileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 简历解析结果 MQ 消费者
 *
 * 功能说明：
 * - 监听 RabbitMQ 结果队列，接收 FastAPI AI 服务返回的解析结果
 * - 将消息委托给 ResumeProfileService 进行业务处理
 * - 区分可恢复异常（系统异常，需重试）和不可恢复异常（业务校验失败，进入死信队列）
 *
 * 异常处理策略：
 * - BusinessException（业务异常）：消息格式或数据不合法，拒绝消息并进入死信队列
 * - 其他 Exception（系统异常）：记录日志并重新抛出，由 RabbitMQ 重试机制处理
 *
 * 技术要点：
 * - @RabbitListener 自动监听指定队列
 * - 使用 Jackson2JsonMessageConverter 自动将 JSON 反序列化为 Java 对象
 * - 容器工厂配置见 RabbitMQConfig#rabbitListenerContainerFactory
 *
 * @author SmartView Team
 * @since 2026-07-25
 */
@Slf4j
@Component
public class ResumeResultConsumer {

    private final ResumeProfileService resumeProfileService;

    public ResumeResultConsumer(ResumeProfileService resumeProfileService) {
        this.resumeProfileService = resumeProfileService;
    }

    /**
     * 消费简历解析结果消息
     *
     * 队列：smartview.resume.parse.result.v1
     * 消息格式：参见 contracts/mq/resume_parse_result.schema.json
     *
     * 消息反序列化由 RabbitMQ 容器的 Jackson2JsonMessageConverter 自动完成，
     * 如果 JSON 格式不匹配，Spring AMQP 会抛出 MessageConversionException，
     * 此类异常由 Spring 默认错误处理机制处理（拒绝消息，不重试）
     *
     * @param message 解析结果消息
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_RESUME_PARSE_RESULT)
    public void handleResumeParseResult(@Payload ResumeParseResultMessage message) {
        /*
         * 监听线程由 RabbitMQ 容器长期复用，进入前必须先按消息建立追踪作用域：
         * handleResult 内部虽然也建了作用域，但它在方法内就闭合了，本类 catch 块里的
         * 失败日志会在线程残留的 MDC 上执行，被挂到别的消息的 traceId 下——
         * 正是"按链路排查失效"的成因。作用域可嵌套，退出时恢复进入前的值。
         */
        try (TraceIdContext.Scope ignored = TraceIdContext.scope(
                message == null ? null : message.getTraceId())) {
            doHandleResumeParseResult(message);
        }
    }

    /**
     * 简历解析结果的实际处理逻辑
     *
     * 从监听方法中抽出，使追踪作用域能覆盖全部日志与异常路径。
     *
     * @param message 解析结果消息
     */
    private void doHandleResumeParseResult(ResumeParseResultMessage message) {
        try {
            log.info("收到简历解析结果消息，taskId={}, resumeFileId={}, success={}",
                    message.getTaskId(), message.getResumeFileId(), message.getSuccess());

            resumeProfileService.handleResult(message);

        } catch (BusinessException e) {
            log.error("简历解析结果处理失败（业务异常），将消息转入死信队列，taskId={}, resumeFileId={}",
                    message.getTaskId(), message.getResumeFileId(), e);
            try {
                /*
                 * handleResult 的原事务会因业务异常回滚，必须在新事务中补偿更新状态；
                 * 补偿失败只记录日志，不能阻止原消息进入死信队列。
                 */
                resumeProfileService.markResultHandlingFailed(message, e.getMessage());
            } catch (Exception compensationException) {
                log.error("简历解析结果失败状态补偿异常，taskId={}, resumeFileId={}",
                        message.getTaskId(), message.getResumeFileId(), compensationException);
            }
            throw new AmqpRejectAndDontRequeueException(
                    "简历解析结果业务校验失败，消息转入死信队列", e);

        } catch (Exception e) {
            // 系统异常（数据库连接失败、网络超时等），可恢复，重新抛出触发重试
            log.error("简历解析结果处理失败（系统异常），taskId={}, resumeFileId={}",
                    message.getTaskId(), message.getResumeFileId(), e);
            // 重新抛出运行时异常，触发 RabbitMQ 的重试/死信机制
            throw new RuntimeException("简历解析结果处理失败，等待重试", e);
        }
    }
}
