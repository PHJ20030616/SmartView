package com.smartview.user;

import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.LoginRequest;
import com.smartview.security.JwtService;
import com.smartview.user.dto.UserDtoMapper;
import com.smartview.user.entity.User;
import com.smartview.user.mapper.UserMapper;
import com.smartview.user.service.AuthService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserDtoMapper userDtoMapper;

    @Mock
    private Validator validator;

    @Test
    void unknownUserShouldStillPerformPasswordHashComparison() {
        when(userMapper.selectOne(any())).thenReturn(null);
        AuthService authService = new AuthService(
                userMapper,
                passwordEncoder,
                jwtService,
                userDtoMapper,
                validator
        );
        LoginRequest request = new LoginRequest("missing-user", "Secret123");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");

        // 即使用户名不存在，也必须执行一次 BCrypt 比较，降低响应时序泄露账号存在性的风险。
        verify(passwordEncoder).matches(eq("Secret123"), anyString());
    }

    @Test
    void loginShouldNotIssueTokenWhenAccountBecomesUnavailableDuringAuthentication() {
        User user = User.builder()
                .id(1L)
                .username("smartuser")
                .passwordHash("encoded-password")
                .status("ACTIVE")
                .deleted(0)
                .build();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("Secret123", "encoded-password")).thenReturn(true);
        when(userMapper.updateLastLoginAtIfActive(eq(1L), any(LocalDateTime.class))).thenReturn(0);
        AuthService authService = new AuthService(
                userMapper,
                passwordEncoder,
                jwtService,
                userDtoMapper,
                validator
        );

        assertThatThrownBy(() -> authService.login(new LoginRequest("smartuser", "Secret123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号状态已变更，请重新登录");

        // 状态条件更新失败说明账号已被并发禁用、锁定或删除，此时绝不能签发可用令牌。
        verify(jwtService, never()).createAccessToken(any());
    }
}
