package com.smartview.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT（JSON Web Token）配置属性类
 * <p>
 * 绑定配置文件中 smartview.jwt 前缀的属性，用于 JWT 令牌的生成和验证。
 * </p>
 */
@ConfigurationProperties(prefix = "smartview.jwt")
public class JwtProperties {

    /** JWT 签名密钥 */
    private String secret;

    /** JWT 签发者标识 */
    private String issuer;

    /** 访问令牌有效期（秒） */
    private long accessTokenTtlSeconds;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }
}
