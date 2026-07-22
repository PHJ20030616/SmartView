package com.smartview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.common.api.ApiResponse;
import com.smartview.common.api.ResponseCode;
import com.smartview.user.entity.User;
import com.smartview.user.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * JWT 认证过滤器。
 *
 * <p>过滤器完成令牌校验后会重新查询用户记录，确保账号状态变化可以即时生效。
 * JWT 本身不作为数据库用户状态的可信来源。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String INVALID_AUTHENTICATION_MESSAGE = "登录状态无效或已过期，请重新登录";

    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final PublicEndpointRequestMatcher publicEndpointRequestMatcher;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserMapper userMapper,
            ObjectMapper objectMapper,
            PublicEndpointRequestMatcher publicEndpointRequestMatcher
    ) {
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
        this.publicEndpointRequestMatcher = publicEndpointRequestMatcher;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        JwtService.TokenClaims claims;
        try {
            claims = jwtService.parseAccessToken(token);
        } catch (JwtException | IllegalArgumentException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response);
            return;
        }

        User user = userMapper.selectById(claims.userId());
        // 每次请求都以数据库状态为准，旧令牌不能绕过账号禁用、锁定或逻辑删除。
        if (user == null
                || !"ACTIVE".equals(user.getStatus())
                || !claims.username().equals(user.getUsername())) {
            writeUnauthorized(response);
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getUsername());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        Collections.emptyList()
                );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 下游异常必须交给 MVC/全局异常处理链，不能误判为 JWT 校验失败并改写成 401。
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅明确白名单中的公开端点忽略旧令牌，受保护接口的无效令牌仍统一返回 401。
        return publicEndpointRequestMatcher.matches(request);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.failure(ResponseCode.UNAUTHORIZED, INVALID_AUTHENTICATION_MESSAGE)
        );
    }
}
