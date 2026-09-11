package com.smartview.security;

import com.smartview.config.properties.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(JwtPropertiesTestConfiguration.class);

    @Test
    void applicationContextShouldRejectMissingJwtSecret() {
        contextRunner
                .withPropertyValues(
                        "smartview.jwt.issuer=smartview-test",
                        "smartview.jwt.access-token-ttl-seconds=3600"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void applicationContextShouldRejectWeakJwtSecret() {
        contextRunner
                .withPropertyValues(
                        "smartview.jwt.secret=too-short",
                        "smartview.jwt.issuer=smartview-test",
                        "smartview.jwt.access-token-ttl-seconds=3600"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    /**
     * 守住随包发布的 application.yml 本身。
     *
     * 上面两个用例走 ApplicationContextRunner，它只装配配置绑定类、不加载 application.yml，
     * 因此无法发现"配置文件里又写回固定回退密钥"这类回归——历史上确实发生过：
     * docs/errors/task2.2_authentication_plan_errors.md §2.1 修复后，被提交 9463938 改回固定值。
     *
     * 注意必须读 PropertySource 而不是 Environment：Environment 会做占位符解析，
     * 读不到 ${JWT_SECRET:} 的原样文本，也就无法判断默认值是否为空。
     */
    @Test
    void applicationYmlMustNotProvideFallbackJwtSecret() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        Object rawSecret = sources.get(0).getProperty("smartview.jwt.secret");

        assertThat(rawSecret)
                .as("application.yml 的 jwt.secret 必须恒为 ${JWT_SECRET:}（冒号后为空），"
                        + "任何非空回退值都会让漏配密钥的部署环境带着可预测密钥上线")
                .isEqualTo("${JWT_SECRET:}");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesTestConfiguration {
    }
}
