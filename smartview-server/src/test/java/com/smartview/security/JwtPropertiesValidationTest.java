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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesValidationTest {

    /** 待守住的属性名：生产环境的 JWT 签名密钥。 */
    private static final String JWT_SECRET_PROPERTY = "smartview.jwt.secret";

    /** 无回退值时的唯一合法写法：占位符冒号后必须为空。 */
    private static final String PLACEHOLDER_WITHOUT_FALLBACK = "${JWT_SECRET:}";

    /** 随包发布的主配置文件名（必须显式声明该属性，否则测试会变成空转）。 */
    private static final String MAIN_CONFIG = "application.yml";

    /**
     * 唯一豁免的配置文件：测试 profile 使用固定测试密钥。
     *
     * <p>这是有意为之——测试密钥不是"可预测的生产回退值"，它只存在于测试 classpath，
     * 不参与任何部署。豁免必须写死在代码里而不是"忽略所有非 application.yml"，
     * 那样将来新增 application-prod.yml 之类的生产配置就会逃过检查。
     */
    private static final String EXEMPT_TEST_PROFILE_CONFIG = "application-test.yml";

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
     * 守住所有随包发布的配置，而不只是 application.yml 一个文件。
     *
     * 上面两个用例走 ApplicationContextRunner，它只装配配置绑定类、不加载任何 yml，
     * 因此无法发现"配置文件里又写回固定回退密钥"这类回归——历史上确实发生过：
     * docs/errors/task2.2_authentication_plan_errors.md §2.1 修复后，被提交 9463938 改回固定值。
     *
     * 扫描范围取 classpath 上全部 {@code application*.yml}：只盯 application.yml 时，
     * 将来新增 application-prod.yml 并写入回退密钥不会被任何测试发现。
     * 唯一的豁免文件是 application-test.yml（见 EXEMPT_TEST_PROFILE_CONFIG 的说明）。
     *
     * 注意必须读 PropertySource 而不是 Environment：Environment 会做占位符解析，
     * 读不到 ${JWT_SECRET:} 的原样文本，也就无法判断默认值是否为空。
     */
    @Test
    void applicationYmlMustNotProvideFallbackJwtSecret() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:application*.yml");
        assertThat(resources)
                .as("classpath 上应至少存在一份 application*.yml，否则本测试会变成空转")
                .isNotEmpty();

        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<String> filesDeclaringSecret = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (EXEMPT_TEST_PROFILE_CONFIG.equals(filename)) {
                continue;
            }
            for (PropertySource<?> source : loader.load(filename, resource)) {
                Object rawSecret = source.getProperty(JWT_SECRET_PROPERTY);
                if (rawSecret == null) {
                    continue;
                }
                filesDeclaringSecret.add(filename);
                assertThat(rawSecret)
                        .as("%s 的 jwt.secret 必须恒为 ${JWT_SECRET:}（冒号后为空），"
                                + "任何非空回退值都会让漏配密钥的部署环境带着可预测密钥上线", filename)
                        .isEqualTo(PLACEHOLDER_WITHOUT_FALLBACK);
            }
        }

        // 若主配置里整个属性被删掉，上面的循环会一个文件都不检查而"通过"，
        // 因此必须单独要求 application.yml 声明了该属性。
        assertThat(filesDeclaringSecret)
                .as("%s 必须显式声明 jwt.secret（缺失时该属性会落到绑定类的默认值上）", MAIN_CONFIG)
                .contains(MAIN_CONFIG);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesTestConfiguration {
    }
}
