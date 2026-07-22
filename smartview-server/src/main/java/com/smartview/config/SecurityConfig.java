package com.smartview.config;

import com.smartview.common.api.ApiResponse;
import com.smartview.common.api.ResponseCode;
import com.smartview.security.JwtAuthenticationFilter;
import com.smartview.security.PublicEndpointRequestMatcher;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

/**
 * Spring Security 安全配置类
 * <p>
 * 配置应用的安全策略，包括认证、授权、会话管理和异常处理。
 * 采用无状态会话（JWT 认证），禁用 CSRF、表单登录和 HTTP Basic 认证。
 * </p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 配置安全过滤器链
     * <p>
     * 定义应用的安全规则：
     * 1. 禁用 CSRF、表单登录、HTTP Basic 认证
     * 2. 使用无状态会话管理（适用于 JWT 认证）
     * 3. 配置公开端点和受保护端点的访问规则
     * 4. 自定义认证失败和授权失败的响应格式
     * </p>
     *
     * @param http                        HttpSecurity 配置对象
     * @param objectMapper                JSON 序列化工具
     * @param handlerMappingIntrospector  处理器映射检查器，用于判断请求是否匹配路由
     * @return 配置好的 SecurityFilterChain
     * @throws Exception 配置异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            PublicEndpointRequestMatcher publicEndpointRequestMatcher,
            HandlerMappingIntrospector handlerMappingIntrospector
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(publicEndpointRequestMatcher).permitAll()
                        .requestMatchers(apiNoHandlerRequestMatcher(handlerMappingIntrospector)).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeErrorResponse(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        ResponseCode.UNAUTHORIZED, "请先登录后再访问"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeErrorResponse(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                        ResponseCode.FORBIDDEN, "无权限访问该资源")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * BCrypt 每次编码都会生成随机盐，数据库只保存不可逆哈希。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 创建 API 未匹配路由的请求匹配器
     * <p>
     * 用于放行 /api/** 路径下未匹配到任何处理器的请求，
     * 让这些请求通过安全过滤器，最终由全局异常处理器返回 404 错误。
     * 只限定 /api/** 路径，避免未来非 MVC 端点被误放行。
     * </p>
     *
     * @param handlerMappingIntrospector 处理器映射检查器
     * @return 请求匹配器
     */
    private RequestMatcher apiNoHandlerRequestMatcher(HandlerMappingIntrospector handlerMappingIntrospector) {
        return request -> {
            if (!request.getRequestURI().startsWith("/api/")) {
                return false;
            }
            try {
                // API 未匹配路由交给全局 404；只限定 /api/**，避免未来非 MVC 端点被误放行。
                return handlerMappingIntrospector.getMatchableHandlerMapping(request) == null;
            } catch (Exception exception) {
                return false;
            }
        };
    }

    /**
     * 写入标准格式的错误响应
     * <p>
     * 将认证失败或授权失败的异常转换为统一的 API 响应格式。
     * </p>
     *
     * @param response     HTTP 响应对象
     * @param objectMapper JSON 序列化工具
     * @param status       HTTP 状态码
     * @param responseCode 响应状态码枚举
     * @param message      错误消息
     * @throws IOException IO 异常
     */
    private void writeErrorResponse(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            ResponseCode responseCode,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.failure(responseCode, message));
    }
}
