package com.smartview.security;

import com.smartview.config.properties.JwtProperties;
import com.smartview.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 访问令牌服务。
 *
 * <p>令牌仅承载稳定的身份信息，用户状态仍由每次请求查询数据库确认，
 * 因而禁用、锁定或删除用户后无需等待令牌过期即可立即阻止访问。</p>
 */
@Service
public class JwtService {

    private static final String USERNAME_CLAIM = "username";

    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.signingKey = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * 为用户签发访问令牌。
     *
     * @param user 已通过登录校验的用户
     * @return 令牌值及有效期
     */
    public IssuedToken createAccessToken(User user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.getAccessTokenTtlSeconds());
        String token = Jwts.builder()
                .subject(user.getId().toString())
                .claim(USERNAME_CLAIM, user.getUsername())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        return new IssuedToken(token, jwtProperties.getAccessTokenTtlSeconds());
    }

    /**
     * 校验签名、签发者和有效期，并提取令牌身份。
     *
     * @param token JWT 字符串
     * @return 令牌中的用户身份
     * @throws io.jsonwebtoken.JwtException 令牌签名、格式或有效期不合法
     * @throws IllegalArgumentException      用户标识缺失或格式错误
     */
    public TokenClaims parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(jwtProperties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String username = claims.get(USERNAME_CLAIM, String.class);
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("JWT 中缺少用户名");
        }
        return new TokenClaims(Long.valueOf(claims.getSubject()), username);
    }

    /**
     * 已签发的访问令牌。
     *
     * @param value            JWT 字符串
     * @param expiresInSeconds 有效期秒数
     */
    public record IssuedToken(String value, long expiresInSeconds) {
    }

    /**
     * JWT 中经过校验的身份声明。
     *
     * @param userId   用户 ID
     * @param username 用户名
     */
    public record TokenClaims(Long userId, String username) {
    }
}
