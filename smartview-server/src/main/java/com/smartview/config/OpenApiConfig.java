package com.smartview.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

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
