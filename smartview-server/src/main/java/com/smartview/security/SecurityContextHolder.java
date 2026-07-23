package com.smartview.security;

import com.smartview.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;

/**
 * 安全上下文持有者工具类
 *
 * 功能说明：
 * - 封装 Spring Security 的 SecurityContextHolder，提供便捷的用户信息访问方法
 * - 从当前线程的安全上下文中获取已认证的用户信息
 * - 用于业务代码中快速获取当前登录用户的 ID、用户名等信息
 *
 * 技术要点：
 * - 依赖 Spring Security 的 ThreadLocal 存储机制
 * - 每个 HTTP 请求在独立的线程中处理，安全上下文在线程间隔离
 * - 异步任务和新线程中无法获取父线程的安全上下文（需手动传递）
 *
 * 使用场景：
 * - Controller/Service 中获取当前登录用户 ID，用于权限校验和数据过滤
 * - 审计日志记录当前操作用户
 * - 业务逻辑中关联当前用户的数据
 *
 * 注意事项：
 * - 只能在 HTTP 请求处理线程中调用，异步线程中返回 null
 * - 未登录或 Token 失效时返回 null，业务代码需做空值判断
 * - 不要在全局变量或静态代码块中调用，会导致空指针异常
 *
 * @author SmartView Team
 * @since 2026-07-23
 */
public class SecurityContextHolder {

    /**
     * 私有构造函数，防止实例化工具类
     */
    private SecurityContextHolder() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 获取当前登录用户 ID
     * 如果用户未登录或 Token 失效，抛出业务异常
     *
     * @return 用户 ID
     * @throws BusinessException 用户未登录时抛出
     */
    public static Long getCurrentUserId() {
        AuthenticatedUser user = getCurrentUser();
        if (user == null) {
            throw new BusinessException(
                    com.smartview.common.api.ResponseCode.UNAUTHORIZED,
                    "用户未登录或登录已过期"
            );
        }
        return user.userId();
    }

    /**
     * 获取当前登录用户 ID（可空）
     * 如果用户未登录，返回 null 而不抛出异常
     *
     * @return 用户 ID，未登录时返回 null
     */
    public static Long getCurrentUserIdOrNull() {
        AuthenticatedUser user = getCurrentUser();
        return user != null ? user.userId() : null;
    }

    /**
     * 获取当前登录用户名
     * 如果用户未登录或 Token 失效，抛出业务异常
     *
     * @return 用户名
     * @throws BusinessException 用户未登录时抛出
     */
    public static String getCurrentUsername() {
        AuthenticatedUser user = getCurrentUser();
        if (user == null) {
            throw new BusinessException(
                    com.smartview.common.api.ResponseCode.UNAUTHORIZED,
                    "用户未登录或登录已过期"
            );
        }
        return user.username();
    }

    /**
     * 获取当前登录用户名（可空）
     * 如果用户未登录，返回 null 而不抛出异常
     *
     * @return 用户名，未登录时返回 null
     */
    public static String getCurrentUsernameOrNull() {
        AuthenticatedUser user = getCurrentUser();
        return user != null ? user.username() : null;
    }

    /**
     * 获取当前已认证的用户对象
     * 从 Spring Security 的 SecurityContext 中提取 AuthenticatedUser
     *
     * @return 已认证的用户对象，未登录时返回 null
     */
    public static AuthenticatedUser getCurrentUser() {
        SecurityContext context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        if (context == null) {
            return null;
        }

        Authentication authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUser) {
            return (AuthenticatedUser) principal;
        }

        return null;
    }

    /**
     * 检查当前用户是否已登录
     *
     * @return true=已登录，false=未登录
     */
    public static boolean isAuthenticated() {
        return getCurrentUser() != null;
    }
}
