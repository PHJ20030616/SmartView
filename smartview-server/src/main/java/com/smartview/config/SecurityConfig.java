package com.smartview.config;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.ApiResponse;
import com.smartview.common.api.ResponseCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.handler.HandlerMappingIntrospector;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/health",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            HandlerMappingIntrospector handlerMappingIntrospector
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(apiNoHandlerRequestMatcher(handlerMappingIntrospector)).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeErrorResponse(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        ResponseCode.UNAUTHORIZED, "请先登录后再访问"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeErrorResponse(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                        ResponseCode.FORBIDDEN, "无权限访问该资源")))
                .build();
    }

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
