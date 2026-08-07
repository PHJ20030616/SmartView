package com.smartview.config;

import com.smartview.config.properties.AiServiceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * AI 服务 HTTP 客户端配置。
 *
 * 功能说明：
 * - 创建用于调用 FastAPI 的 RestTemplate Bean（AiInterviewClient 专用）
 * - 使用 SimpleClientHttpRequestFactory 配置连接与读取超时，防止 AI 服务
 *   不可用时无限阻塞面试创建请求
 *
 * 设计取舍：
 * - 项目当前依赖为 spring-boot-starter-web（自带 RestTemplate），
 *   未引入 WebFlux/WebClient；故首题同步调用使用 RestTemplate，
 *   与现有技术栈保持一致，不额外引入依赖。
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Configuration
public class AiServiceConfig {

    /**
     * 面向 FastAPI 的 RestTemplate。
     *
     * @param properties AI 服务配置属性
     * @return 已设置超时的 RestTemplate
     */
    @Bean
    public RestTemplate aiServiceRestTemplate(AiServiceProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        return new RestTemplate(factory);
    }
}
