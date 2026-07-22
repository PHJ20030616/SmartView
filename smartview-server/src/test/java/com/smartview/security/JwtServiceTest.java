package com.smartview.security;

import com.smartview.config.properties.JwtProperties;
import com.smartview.user.entity.User;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "smartview-unit-test-jwt-secret-at-least-32-bytes";

    private JwtProperties jwtProperties;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        jwtProperties.setIssuer("smartview-unit-test");
        jwtProperties.setAccessTokenTtlSeconds(3600);
        jwtService = new JwtService(jwtProperties);
    }

    @Test
    void shouldIssueAndParseAccessToken() {
        User user = User.builder()
                .id(42L)
                .username("alice")
                .build();

        JwtService.IssuedToken issuedToken = jwtService.createAccessToken(user);
        JwtService.TokenClaims claims = jwtService.parseAccessToken(issuedToken.value());

        assertThat(issuedToken.value()).isNotBlank();
        assertThat(issuedToken.expiresInSeconds()).isEqualTo(3600);
        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.username()).isEqualTo("alice");
    }

    @Test
    void shouldRejectTamperedToken() {
        User user = User.builder()
                .id(42L)
                .username("alice")
                .build();
        String token = jwtService.createAccessToken(user).value();
        String tamperedToken = token.substring(0, token.length() - 1)
                + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> jwtService.parseAccessToken(tamperedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectExpiredToken() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String expiredToken = Jwts.builder()
                .subject("42")
                .claim("username", "alice")
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAccessToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
