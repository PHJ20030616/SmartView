package com.smartview.user.controller;

import com.smartview.common.api.ApiResponse;
import com.smartview.generated.web.model.UserInfo;
import com.smartview.security.AuthenticatedUser;
import com.smartview.user.service.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户信息接口。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> getCurrentUser(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser
    ) {
        return ApiResponse.success(authService.getCurrentUser(authenticatedUser.userId()));
    }
}
