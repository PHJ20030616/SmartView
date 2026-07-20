package com.smartview.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置类
 * <p>
 * 配置 SpringDoc OpenAPI（Swagger）文档生成器。
 * 提供在线 API 文档和交互式测试界面，便于前端开发和接口调试。
 * </p>
 */
@Configuration
public class OpenApiConfig {

    /**
     * 创建 OpenAPI 文档配置 Bean
     * <p>
     * 定义 API 文档的基本信息，包括标题、描述、版本号和联系方式。
     * 文档可通过 /swagger-ui.html 访问。
     * </p>
     *
     * @return OpenAPI 配置对象
     */
    @Bean
    public OpenAPI smartViewOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartView Web API")
                        .description("SmartView Spring Boot 后端对外 API")
                        .version("1.0.0")
                        .contact(new Contact().name("SmartView Team")));
    }
}
