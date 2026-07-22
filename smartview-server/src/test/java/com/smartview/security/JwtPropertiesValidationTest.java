package com.smartview.security;

import com.smartview.config.properties.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

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

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesTestConfiguration {
    }
}
