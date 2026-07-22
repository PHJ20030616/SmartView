package com.smartview.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.user.entity.User;
import com.smartview.user.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PublicEndpointRequestMatcher publicEndpointRequestMatcher;

    @Mock
    private FilterChain filterChain;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void downstreamIllegalArgumentExceptionShouldPropagate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/me");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalArgumentException downstreamException = new IllegalArgumentException("下游业务参数异常");

        when(publicEndpointRequestMatcher.matches(request)).thenReturn(false);
        when(jwtService.parseAccessToken("valid-token"))
                .thenReturn(new JwtService.TokenClaims(1L, "smartuser"));
        when(userMapper.selectById(1L)).thenReturn(User.builder()
                .id(1L)
                .username("smartuser")
                .status("ACTIVE")
                .deleted(0)
                .build());
        doThrow(downstreamException).when(filterChain).doFilter(request, response);

        assertThatThrownBy(() -> new JwtAuthenticationFilter(
                jwtService,
                userMapper,
                new ObjectMapper().findAndRegisterModules(),
                publicEndpointRequestMatcher
        ).doFilter(request, response, filterChain))
                .isSameAs(downstreamException);

        // 下游业务异常应交给后续异常处理链，认证过滤器不得将其改写为 JWT 401。
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
