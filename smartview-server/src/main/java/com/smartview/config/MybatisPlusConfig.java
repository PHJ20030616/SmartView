package com.smartview.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 *
 * 功能说明：
 * - 配置 Mapper 接口扫描路径
 * - 注册分页拦截器（Task 7.1 简历/面试历史列表使用 selectPage 分页）
 * - 逻辑删除、字段填充等全局配置在 application.yml 中设置
 *
 * 技术要点：
 * - @MapperScan 自动扫描指定包下的 Mapper 接口并注册为 Spring Bean
 * - 扫描路径为 com.smartview.*.mapper，支持所有模块的 Mapper
 * - 分页拦截器必须在 MybatisPlusInterceptor 中注册，否则 Page 参数不会生成
 *   LIMIT 语句（selectPage 退化为全量查询，total 恒为 0）
 * - 拦截器只对带 Page 参数的查询生效，不影响现有 selectList/selectOne 调用
 *
 * @author SmartView Team
 * @since 2026-07-20
 */
@Configuration
@MapperScan("com.smartview.*.mapper")
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 插件链：注册 MySQL 分页拦截器。
     *
     * 业务背景：历史列表接口（GET /api/resumes、GET /api/interview-sessions）
     * 通过 Mapper.selectPage(Page, wrapper) 分页查询，缺少该拦截器时
     * MyBatis-Plus 不会拼接 LIMIT，导致分页失效并全量返回数据。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
