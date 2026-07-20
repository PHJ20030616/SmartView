package com.smartview.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 *
 * 功能说明：
 * - 配置 Mapper 接口扫描路径
 * - 逻辑删除、字段填充等全局配置在 application.yml 中设置
 *
 * 技术要点：
 * - @MapperScan 自动扫描指定包下的 Mapper 接口并注册为 Spring Bean
 * - 扫描路径为 com.smartview.*.mapper，支持所有模块的 Mapper
 * - 如需自定义 MetaObjectHandler（自动填充时间字段），可在此类中定义 Bean
 *
 * @author SmartView Team
 * @since 2026-07-20
 */
@Configuration
@MapperScan("com.smartview.*.mapper")
public class MybatisPlusConfig {
    // 当前仅配置 Mapper 扫描
    // 如需添加分页插件、性能分析插件等，可在此类中定义 Bean
}
