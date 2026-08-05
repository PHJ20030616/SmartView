package com.smartview.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

/**
 * RabbitMQ 配置类
 *
 * 功能说明：
 * - 配置 RabbitMQ 的 Exchange、Queue、Binding
 * - 配置消息序列化器（使用 Jackson 将消息转换为 JSON）
 * - 配置结果队列的死信队列（DLQ）和有界重试策略，防止系统异常导致无限重新入队
 * - 确保 Spring Boot 启动时自动创建所需的交换机和队列
 *
 * 技术要点：
 * - DirectExchange：直连交换机，根据 routing key 精确匹配队列
 * - Queue：消息队列，durable=true 表示持久化（服务重启后队列不丢失）
 * - Binding：绑定关系，将队列绑定到交换机，并指定 routing key
 * - Jackson2JsonMessageConverter：使用 Jackson 序列化/反序列化消息为 JSON 格式
 * - 死信队列：结果队列配置 x-dead-letter-exchange，重试耗尽后消息转入 DLQ 避免无限循环
 * - ExponentialBackOffPolicy：指数退避重试策略（1s → 2s → 4s），最大重试 3 次
 *
 * 消息路由：
 * - Spring Boot → Exchange → smartview.resume.parse（任务队列）→ FastAPI AI 服务
 * - FastAPI AI 服务 → Exchange → smartview.resume.parse.result.v1（结果队列）→ Spring Boot
 * - 消费失败超限 → smartview.resume.parse.result.dlq（死信队列，人工处理）
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Configuration
public class RabbitMQConfig {

    // ==================== 交换机和任务队列常量 ====================

    public static final String EXCHANGE_SMARTVIEW_DIRECT = "smartview.direct";

    public static final String QUEUE_RESUME_PARSE = "smartview.resume.parse";
    public static final String ROUTING_KEY_RESUME_PARSE = "resume.parse.task";
    /*
     * 任务队列增加版本后缀，避免给旧的无 DLX 队列补声明参数时触发 RabbitMQ
     * PRECONDITION_FAILED；旧队列中的消息仍可由运维按原流程处理。
     */
    public static final String QUEUE_RESUME_VECTORIZE = "smartview.resume.vectorize.v1";
    public static final String ROUTING_KEY_RESUME_VECTORIZE = "resume.vectorize.task";

    /*
     * 画像分析任务队列：用户选择面试方向后由 Spring 投递，FastAPI worker 消费
     * 生成方向画像分析。使用 v1 后缀避免与旧队列声明参数冲突（与向量队列同策略）。
     */
    public static final String QUEUE_PROFILE_ANALYZE = "smartview.profile.analyze.v1";
    public static final String ROUTING_KEY_PROFILE_ANALYZE = "profile.analyze.task";

    // ==================== 结果队列及死信队列常量 ====================

    /**
     * 简历解析结果队列名称
     * FastAPI AI 服务解析完成后将结果投递到此队列
     * 使用 v1 后缀避免旧版本无 DLX 参数的队列与新版本参数冲突导致 Broker 声明失败；
     * 首次部署或旧队列已手动删除时可移除后缀
     */
    public static final String QUEUE_RESUME_PARSE_RESULT = "smartview.resume.parse.result.v1";

    /**
     * 简历解析结果路由键
     */
    public static final String ROUTING_KEY_RESUME_PARSE_RESULT = "resume.parse.result";
    public static final String QUEUE_RESUME_VECTORIZE_RESULT = "smartview.resume.vectorize.result.v1";
    public static final String ROUTING_KEY_RESUME_VECTORIZE_RESULT = "resume.vectorize.result";
    public static final String QUEUE_PROFILE_ANALYZE_RESULT = "smartview.profile.analyze.result.v1";
    public static final String ROUTING_KEY_PROFILE_ANALYZE_RESULT = "profile.analyze.result";

    /**
     * 死信交换机，所有队列的重试耗尽消息统一路由到此
     */
    private static final String DLX_EXCHANGE = "smartview.dlx";

    /**
     * 结果队列的死信队列
     */
    private static final String QUEUE_RESUME_PARSE_RESULT_DLQ = "smartview.resume.parse.result.dlq";

    /**
     * 结果队列的死信路由键
     */
    private static final String ROUTING_KEY_RESUME_PARSE_RESULT_DLQ = "resume.parse.result.dlq";
    private static final String QUEUE_RESUME_VECTORIZE_DLQ =
            "smartview.resume.vectorize.dlq";
    private static final String ROUTING_KEY_RESUME_VECTORIZE_DLQ =
            "resume.vectorize.task.dlq";
    private static final String QUEUE_RESUME_VECTORIZE_RESULT_DLQ =
            "smartview.resume.vectorize.result.dlq";
    private static final String ROUTING_KEY_RESUME_VECTORIZE_RESULT_DLQ =
            "resume.vectorize.result.dlq";

    /*
     * 画像分析任务/结果死信队列：消费失败超限或业务校验失败的消息转入，供人工或补偿调度处理。
     */
    private static final String QUEUE_PROFILE_ANALYZE_DLQ =
            "smartview.profile.analyze.dlq";
    private static final String ROUTING_KEY_PROFILE_ANALYZE_DLQ =
            "profile.analyze.task.dlq";
    private static final String QUEUE_PROFILE_ANALYZE_RESULT_DLQ =
            "smartview.profile.analyze.result.dlq";
    private static final String ROUTING_KEY_PROFILE_ANALYZE_RESULT_DLQ =
            "profile.analyze.result.dlq";

    /**
     * 最大处理次数（首次消费 + 3 次重试 = 共 4 次机会）
     * SimpleRetryPolicy.setMaxAttempts 表示总尝试次数，包含首次消费
     */
    private static final int MAX_RETRY_ATTEMPTS = 4;

    /**
     * 指数退避初始间隔（毫秒）
     */
    private static final long INITIAL_BACKOFF_INTERVAL_MS = 1000;

    // ==================== Exchange ====================

    @Bean
    public DirectExchange smartviewDirectExchange() {
        return new DirectExchange(EXCHANGE_SMARTVIEW_DIRECT, true, false);
    }

    /**
     * 创建死信交换机
     * 所有重试耗尽的消息统一路由到此交换机，再分发到各 DLQ
     */
    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    // ==================== 任务队列（Spring Boot → FastAPI） ====================

    @Bean
    public Queue resumeParseQueue() {
        return new Queue(QUEUE_RESUME_PARSE, true, false, false);
    }

    @Bean
    public Binding resumeParseBinding() {
        return BindingBuilder
                .bind(resumeParseQueue())
                .to(smartviewDirectExchange())
                .with(ROUTING_KEY_RESUME_PARSE);
    }

    @Bean
    public Queue resumeVectorizeQueue() {
        /*
         * 任务消息不能因为 worker 发布结果失败而静默丢失。
         * 进入 DLQ 后由向量补偿调度器依据 ai_task 的租约再次投递。
         */
        return QueueBuilder.durable(QUEUE_RESUME_VECTORIZE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_RESUME_VECTORIZE_DLQ)
                .build();
    }

    @Bean
    public Binding resumeVectorizeBinding() {
        return BindingBuilder
                .bind(resumeVectorizeQueue())
                .to(smartviewDirectExchange())
                .with(ROUTING_KEY_RESUME_VECTORIZE);
    }

    @Bean
    public Queue profileAnalyzeQueue() {
        /*
         * 画像分析任务消息不能因为 worker 发布结果失败而静默丢失。
         * 进入 DLQ 后由运维或后续补偿调度依据 ai_task 租约再次投递。
         */
        return QueueBuilder.durable(QUEUE_PROFILE_ANALYZE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_PROFILE_ANALYZE_DLQ)
                .build();
    }

    @Bean
    public Binding profileAnalyzeBinding() {
        return BindingBuilder
                .bind(profileAnalyzeQueue())
                .to(smartviewDirectExchange())
                .with(ROUTING_KEY_PROFILE_ANALYZE);
    }

    // ==================== 结果队列（FastAPI → Spring Boot） ====================

    /**
     * 创建简历解析结果队列
     * 配置死信交换机，消费者抛出不可恢复异常超限后消息转入 DLQ
     */
    @Bean
    public Queue resumeParseResultQueue() {
        return QueueBuilder.durable(QUEUE_RESUME_PARSE_RESULT)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_RESUME_PARSE_RESULT_DLQ)
                .build();
    }

    @Bean
    public Binding resumeParseResultBinding() {
        return BindingBuilder
                .bind(resumeParseResultQueue())
                .to(smartviewDirectExchange())
                .with(ROUTING_KEY_RESUME_PARSE_RESULT);
    }

    @Bean
    public Queue resumeVectorizeResultQueue() {
        return QueueBuilder.durable(QUEUE_RESUME_VECTORIZE_RESULT)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_RESUME_VECTORIZE_RESULT_DLQ)
                .build();
    }

    @Bean
    public Binding resumeVectorizeResultBinding() {
        return BindingBuilder
                .bind(resumeVectorizeResultQueue())
                .to(smartviewDirectExchange())
                .with(ROUTING_KEY_RESUME_VECTORIZE_RESULT);
    }

    @Bean
    public Queue profileAnalyzeResultQueue() {
        return QueueBuilder.durable(QUEUE_PROFILE_ANALYZE_RESULT)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_PROFILE_ANALYZE_RESULT_DLQ)
                .build();
    }

    @Bean
    public Binding profileAnalyzeResultBinding() {
        return BindingBuilder
                .bind(profileAnalyzeResultQueue())
                .to(smartviewDirectExchange())
                .with(ROUTING_KEY_PROFILE_ANALYZE_RESULT);
    }

    // ==================== 任务/结果死信队列 ====================

    @Bean
    public Queue resumeVectorizeDlq() {
        return new Queue(QUEUE_RESUME_VECTORIZE_DLQ, true, false, false);
    }

    @Bean
    public Binding resumeVectorizeDlqBinding() {
        return BindingBuilder
                .bind(resumeVectorizeDlq())
                .to(deadLetterExchange())
                .with(ROUTING_KEY_RESUME_VECTORIZE_DLQ);
    }

    /**
     * 创建结果队列的死信队列
     * 重试耗尽的消息存放于此，需人工或定时任务消费/告警
     */
    @Bean
    public Queue resumeParseResultDlq() {
        return new Queue(QUEUE_RESUME_PARSE_RESULT_DLQ, true, false, false);
    }

    @Bean
    public Binding resumeParseResultDlqBinding() {
        return BindingBuilder
                .bind(resumeParseResultDlq())
                .to(deadLetterExchange())
                .with(ROUTING_KEY_RESUME_PARSE_RESULT_DLQ);
    }

    @Bean
    public Queue resumeVectorizeResultDlq() {
        return new Queue(QUEUE_RESUME_VECTORIZE_RESULT_DLQ, true, false, false);
    }

    @Bean
    public Binding resumeVectorizeResultDlqBinding() {
        return BindingBuilder
                .bind(resumeVectorizeResultDlq())
                .to(deadLetterExchange())
                .with(ROUTING_KEY_RESUME_VECTORIZE_RESULT_DLQ);
    }

    @Bean
    public Queue profileAnalyzeDlq() {
        return new Queue(QUEUE_PROFILE_ANALYZE_DLQ, true, false, false);
    }

    @Bean
    public Binding profileAnalyzeDlqBinding() {
        return BindingBuilder
                .bind(profileAnalyzeDlq())
                .to(deadLetterExchange())
                .with(ROUTING_KEY_PROFILE_ANALYZE_DLQ);
    }

    @Bean
    public Queue profileAnalyzeResultDlq() {
        return new Queue(QUEUE_PROFILE_ANALYZE_RESULT_DLQ, true, false, false);
    }

    @Bean
    public Binding profileAnalyzeResultDlqBinding() {
        return BindingBuilder
                .bind(profileAnalyzeResultDlq())
                .to(deadLetterExchange())
                .with(ROUTING_KEY_PROFILE_ANALYZE_RESULT_DLQ);
    }

    // ==================== 消费者容器工厂（带重试拦截器） ====================

    /**
     * 配置 RabbitMQ 监听器容器工厂
     * - Jackson JSON 转换器，自动将 JSON 消息反序列化为 Java 对象
     * - RetryOperationsInterceptor：有界重试 + 指数退避，重试耗尽后消息进入 DLQ
     *
     * @param connectionFactory RabbitMQ 连接工厂
     * @return SimpleRabbitListenerContainerFactory 实例
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter(objectMapper));
        // 注册重试拦截器：系统异常最多重试 3 次，指数退避 1s→2s→4s
        factory.setAdviceChain(retryOperationsInterceptor());
        return factory;
    }

    /**
     * 构建有界重试拦截器
     * 消费者抛出 RuntimeException 时触发重试，BusinessException 已由消费者自行 ACK 不重试
     *
     * 重试策略：
     * - setMaxAttempts(4)：首次消费 + 3 次重试 = 共 4 次处理机会
     * - 指数退避间隔：1s → 2s → 4s（三次重试间隔）
     * - 业务拒绝异常不重试，直接由队列拒绝并路由到 DLQ
     * - 其他系统异常重试耗尽后抛出 AmqpRejectAndDontRequeueException，由 DLQ 接收
     */
    @Bean
    public RetryOperationsInterceptor retryOperationsInterceptor() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // 退避策略：指数递增，初始 1s，最大 1s * 2^2 = 4s
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(INITIAL_BACKOFF_INTERVAL_MS);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(INITIAL_BACKOFF_INTERVAL_MS * 4);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        // 明确排除不可恢复异常，避免业务消息先被重复处理多次再进入 DLQ。
        // traverseCauses=true 兼容异常被监听器包装后的场景。
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = Map.of(
                org.springframework.amqp.AmqpRejectAndDontRequeueException.class, false);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                MAX_RETRY_ATTEMPTS, retryableExceptions, true);
        retryTemplate.setRetryPolicy(retryPolicy);

        RetryOperationsInterceptor interceptor = new RetryOperationsInterceptor();
        interceptor.setRetryOperations(retryTemplate);
        // recoverer 为 RejectAndDontRequeue：重试耗尽后拒绝消息，触发 DLQ 路由
        interceptor.setRecoverer((args, cause) -> {
            throw new org.springframework.amqp.AmqpRejectAndDontRequeueException(
                    "重试耗尽（" + MAX_RETRY_ATTEMPTS + " 次），消息转入 DLQ", cause);
        });
        return interceptor;
    }

    // ==================== 消息转换器 ====================

    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        // MQ 契约中的 createdAt 是 ISO 8601 字符串；显式关闭时间戳数组序列化，
        // 避免 Java LocalDateTime 被编码为 [年,月,日,时,分,秒,纳秒]，导致 FastAPI 无法按契约解析。
        ObjectMapper mqObjectMapper = objectMapper.copy()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mqObjectMapper);
    }
}
