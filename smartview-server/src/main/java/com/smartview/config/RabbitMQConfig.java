package com.smartview.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 *
 * 功能说明：
 * - 配置 RabbitMQ 的 Exchange、Queue、Binding
 * - 配置消息序列化器（使用 Jackson 将消息转换为 JSON）
 * - 确保 Spring Boot 启动时自动创建所需的交换机和队列
 *
 * 技术要点：
 * - DirectExchange：直连交换机，根据 routing key 精确匹配队列
 * - Queue：消息队列，durable=true 表示持久化（服务重启后队列不丢失）
 * - Binding：绑定关系，将队列绑定到交换机，并指定 routing key
 * - Jackson2JsonMessageConverter：使用 Jackson 序列化消息为 JSON 格式
 *
 * 消息路由：
 * - Exchange: smartview.direct
 * - Queue: smartview.resume.parse
 * - Routing Key: resume.parse.task
 * - Spring Boot → Exchange → Queue → FastAPI AI 服务
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
@Configuration
public class RabbitMQConfig {

    /**
     * 交换机名称
     */
    public static final String EXCHANGE_SMARTVIEW_DIRECT = "smartview.direct";

    /**
     * 简历解析队列名称
     */
    public static final String QUEUE_RESUME_PARSE = "smartview.resume.parse";

    /**
     * 简历解析路由键
     */
    public static final String ROUTING_KEY_RESUME_PARSE = "resume.parse.task";

    /**
     * 创建直连交换机
     * durable=true：交换机持久化，RabbitMQ 重启后仍存在
     * autoDelete=false：没有队列绑定时也不自动删除
     *
     * @return DirectExchange 实例
     */
    @Bean
    public DirectExchange smartviewDirectExchange() {
        return new DirectExchange(EXCHANGE_SMARTVIEW_DIRECT, true, false);
    }

    /**
     * 创建简历解析队列
     * durable=true：队列持久化，RabbitMQ 重启后仍存在
     * exclusive=false：非独占队列，可被多个连接访问
     * autoDelete=false：消费者断开后队列不自动删除
     *
     * @return Queue 实例
     */
    @Bean
    public Queue resumeParseQueue() {
        return new Queue(QUEUE_RESUME_PARSE, true, false, false);
    }

    /**
     * 绑定简历解析队列到交换机
     * 使用 routing key: resume.parse.task
     * Spring Boot 发送消息时指定此 routing key，RabbitMQ 自动路由到对应队列
     *
     * @return Binding 实例
     */
    @Bean
    public Binding resumeParseBinding() {
        return BindingBuilder
                .bind(resumeParseQueue())
                .to(smartviewDirectExchange())
                .with(ROUTING_KEY_RESUME_PARSE);
    }

    /**
     * 配置消息序列化器
     * 使用 Jackson 将 Java 对象序列化为 JSON 格式
     * 消费者（FastAPI）接收到的是 JSON 字符串，易于跨语言解析
     *
     * @return Jackson2JsonMessageConverter 实例
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
