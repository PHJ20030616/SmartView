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
 * 逗号分隔字符串的写法——两者 Spring Boot 都能识别。
 *
 * 为什么断言必须精确比对整条路径、而不是"前缀 + 后缀"形状匹配：
 * 单测自身也要能被证伪。用形状匹配时，把路径写成 {@code /nonsense/dir/smartview-infra/.env[.properties]}
 * 依然会通过——它同样以 {@code optional:file:} 开头、以 {@code smartview-infra/.env[.properties]} 结尾，
 * 但真实启动时根本解析不到文件，通道彻底失效、所有回退默认值静默生效。
 * 而"路径写错"恰恰是这段配置最可能出错的形态（例如把 {@code ../} 误写成 {@code ../../}），
 * 因此这里只接受下面两个经文档化的、能在真实工作目录下命中的相对路径。
 *
 * @author SmartView Team
 * @since 2026-09-11
 */
class SharedInfraEnvImportTest {

    /** application.yml 中配置导入项的属性名前缀。 */
    private static final String IMPORT_PROPERTY_PREFIX = "spring.config.import";

    /**
     * 允许的导入声明完整取值。
     *
     * <p>两条分别对应两种被文档化的启动工作目录（见 docs/local-development.md）：
     * 在 {@code smartview-server/} 目录下启动（{@code mvn spring-boot:run} 的默认工作目录）
     * 命中前者的 {@code ../}；在仓库根启动命中后者。两条都带 {@code [.properties]}
     * 扩展名提示——{@code .env} 不在 Spring 已知扩展名内，缺提示会直接报
     * {@code Unable to load config data}。
     */
    private static final List<String> ALLOWED_IMPORT_LOCATIONS = List.of(
            "optional:file:../smartview-infra/.env[.properties]",
            "optional:file:smartview-infra/.env[.properties]"
    );

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

        // 必须两条都在，而不是"至少有一条能对上"。
        // 两条各自负责一种工作目录（见 ALLOWED_IMPORT_LOCATIONS 的说明）：
        // 只留下一条时，另一种启动方式会静默失去 .env——例如删掉带 ../ 的那条，
        // README 推荐的 mvn spring-boot:run（默认工作目录就是 smartview-server/）就再也读不到配置。
        // 用 anyMatch 会放过这种"坏了一半"的状态，因此这里要求全部命中。
        assertThat(importLocations)
                .as("application.yml 必须同时声明下列两个受支持路径（缺一条就会让某一种工作目录静默失去 .env）：%s",
                        ALLOWED_IMPORT_LOCATIONS)
                .containsAll(ALLOWED_IMPORT_LOCATIONS);
    }
}
