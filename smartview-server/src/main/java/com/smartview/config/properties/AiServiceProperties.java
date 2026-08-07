package com.smartview.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 服务（FastAPI）HTTP 调用配置属性类。
 *
 * 功能说明：
 * - 从 application.yml 读取 smartview.ai-service 配置项
 * - 提供 Spring Boot 通过 AiInterviewClient 同步调用 FastAPI 首题生成等接口所需的
 *   基础地址、鉴权密钥与超时参数
 * - 生产主链路仍是 MQ 异步（画像分析等）；本配置仅供面试首题这类需要同步返回的
 *   轻量接口使用
 *
 * 配置项：
 * - baseUrl：FastAPI 服务根地址，默认本地开发 http://localhost:8000
 * - apiKey：跨服务调用鉴权密钥，与 smartview-ai 的 AI_SERVICE_API_KEY 保持一致
 * - connectTimeoutMs：连接建立超时（毫秒）
 * - readTimeoutMs：读取响应超时（毫秒），需覆盖 FastAPI 首题生成的 LLM 耗时
 *
 * @author SmartView Team
 * @since 2026-08-05
 */
@Data
@Component
@ConfigurationProperties(prefix = "smartview.ai-service")
public class AiServiceProperties {

    /**
     * FastAPI AI 服务根地址
     */
    private String baseUrl = "http://localhost:8000";

    /**
     * 跨服务调用鉴权密钥，请求头 X-API-Key 透传，与 AI 服务端配置保持一致
     */
    private String apiKey = "";

    /**
     * 建立 TCP 连接超时（毫秒），默认 3 秒
     */
    private Integer connectTimeoutMs = 3000;

    /**
     * 读取响应超时（毫秒），默认 60 秒。
     * 需覆盖 FastAPI 首题生成的"检索 + LLM 调用"耗时：FastAPI 对 DeepSeek 的
     * httpx 超时上限为 120s，Spring 若设得太短会在模型慢时提前中断并回滚事务。
     */
    private Integer readTimeoutMs = 60000;
}
