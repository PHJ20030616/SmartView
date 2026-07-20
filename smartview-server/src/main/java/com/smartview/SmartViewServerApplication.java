package com.smartview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * SmartView 后端服务应用入口
 * <p>
 * 基于 Spring Boot 构建的后端服务，提供 RESTful API 供前端调用，
 * 并通过 AiServiceClient 调用 FastAPI AI 服务获取 AI 能力。
 * </p>
 * <p>
 * 注解说明：
 * - @SpringBootApplication: 标识为 Spring Boot 应用，启用自动配置和组件扫描
 * - @ConfigurationPropertiesScan: 扫描并注册配置属性类（如 JwtProperties、MinioProperties）
 * </p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SmartViewServerApplication {

    /**
     * 应用入口方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SmartViewServerApplication.class, args);
    }
}
