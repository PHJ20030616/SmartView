package com.smartview.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT（JSON Web Token）配置属性类
 * <p>
 * 绑定配置文件中 smartview.jwt 前缀的属性，用于 JWT 令牌的生成和验证。
 * </p>
 */
@Validated
@ConfigurationProperties(prefix = "smartview.jwt")
public class JwtProperties {

    /** JWT 签名密钥 */
    @NotBlank(message = "JWT 签名密钥不能为空")
    @Size(min = 32, message = "JWT 签名密钥长度不能少于 32 个字符")
    private String secret;

    /** JWT 签发者标识 */
    @NotBlank(message = "JWT 签发者不能为空")
    private String issuer;

    /** 访问令牌有效期（秒） */
    @Positive(message = "JWT 访问令牌有效期必须大于 0")
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
