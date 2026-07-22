package com.smartview.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 统一维护无需认证的公开端点。
 *
 * <p>授权规则和 JWT 过滤器必须复用同一个匹配器，避免某个端点虽然被声明为公开，
 * 却因客户端携带过期或伪造令牌而被过滤器提前拒绝。</p>
 */
@Component
public class PublicEndpointRequestMatcher implements RequestMatcher {

    private static final Set<String> EXACT_PATHS = Set.of(
            "/api/health",
            "/api/auth/register",
            "/api/auth/login",
            "/v3/api-docs",
            "/v3/api-docs.yaml",
            "/swagger-ui.html"
    );

    private static final Set<String> PATH_PREFIXES = Set.of(
            "/v3/api-docs/",
            "/swagger-ui/"
    );

    @Override
    public boolean matches(HttpServletRequest request) {
        String requestPath = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (!contextPath.isEmpty() && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }

        if (EXACT_PATHS.contains(requestPath)) {
            return true;
        }
        String normalizedPath = requestPath;
        return PATH_PREFIXES.stream().anyMatch(normalizedPath::startsWith);
    }
}
