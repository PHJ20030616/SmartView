package com.smartview.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守卫：application.yml 必须声明导入 smartview-infra/.env。
 *
 * 为什么需要这个测试：这段声明一旦被删掉，application.yml 里所有 ${VAR:default}
 * 会静默回落到默认值——不报错、不告警，只表现为"我改了 .env 但服务行为没变"。
 * 而回退默认值恰好与 .env 等价，所以本地开发环境下无法察觉。
 *
 * 为什么读 PropertySource 而不是 Environment：Environment 会解析占位符，
 * 只能看到求值结果；这里要断言的是 application.yml 里<b>写了什么</b>，
 * 必须读未经解析的原始属性源。
 *
 * 断言的键名形式：YAML 列表（{@code import:} 下的 {@code - xxx} 项）会被
 * YamlPropertySourceLoader 展平成索引键 {@code spring.config.import[0]}、
 * {@code spring.config.import[1]}，因此 {@code getProperty("spring.config.import")}
 * 恒为 null。这里按前缀收集所有相关属性，既覆盖列表写法，也兼容将来改成
 * 逗号分隔字符串的写法——两者 Spring Boot 都能识别，而本测试只关心
 * "是否声明了指向共享 .env 的导入"。
 *
 * @author SmartView Team
 * @since 2026-09-11
 */
class SharedInfraEnvImportTest {

    /** application.yml 中配置导入项的属性名前缀。 */
    private static final String IMPORT_PROPERTY_PREFIX = "spring.config.import";

    /** 导入声明的目标：共享基础设施配置，相对工作目录解析。 */
    private static final String SHARED_ENV_SUFFIX = "smartview-infra/.env[.properties]";

    @Test
    void applicationYmlMustImportSharedInfraEnv() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        List<String> importLocations = new ArrayList<>();
        for (PropertySource<?> source : sources) {
            // 只有可枚举的属性源才能列出键名；YAML 加载器返回的是
            // OriginTrackedMapPropertySource，属于 EnumerablePropertySource。
            // 这里用 instanceof 收窄而非直接强转，避免将来换加载器时抛 ClassCastException。
            if (!(source instanceof EnumerablePropertySource<?> enumerableSource)) {
                continue;
            }
            for (String propertyName : enumerableSource.getPropertyNames()) {
                if (propertyName.startsWith(IMPORT_PROPERTY_PREFIX)) {
                    importLocations.add(String.valueOf(source.getProperty(propertyName)));
                }
            }
        }

        assertThat(importLocations)
                .as("application.yml 必须声明 spring.config.import，否则 smartview-infra/.env 永远不会生效，"
                        + "所有 ${VAR:default} 都会静默走回退值")
                .isNotEmpty();

        assertThat(importLocations)
                .as("导入声明必须指向 smartview-infra/.env 并带 [.properties] 扩展名提示"
                        + "（.env 不在 Spring 已知扩展名内，缺提示会报 Unable to load config data）")
                .anyMatch(location -> location.startsWith("optional:file:")
                        && location.endsWith(SHARED_ENV_SUFFIX));
    }
}
