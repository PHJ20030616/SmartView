package com.smartview.security;

/**
 * 已认证用户身份。
 *
 * <p>安全上下文只保存后续业务真正需要的用户标识，不放入密码哈希等敏感字段，
 * 避免认证对象被日志或调试工具意外输出时泄露凭据。</p>
 *
 * @param userId   用户 ID
 * @param username 用户名
 */
public record AuthenticatedUser(Long userId, String username) {
}
