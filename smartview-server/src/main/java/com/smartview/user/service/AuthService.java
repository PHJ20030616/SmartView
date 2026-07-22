package com.smartview.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartview.common.api.ResponseCode;
import com.smartview.common.exception.BusinessException;
import com.smartview.generated.web.model.LoginData;
import com.smartview.generated.web.model.LoginRequest;
import com.smartview.generated.web.model.RegisterRequest;
import com.smartview.generated.web.model.UserInfo;
import com.smartview.security.JwtService;
import com.smartview.user.dto.UserDtoMapper;
import com.smartview.user.entity.User;
import com.smartview.user.mapper.UserMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 注册、登录与当前用户业务服务。
 */
@Service
public class AuthService {

    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final Map<String, String> FIELD_NAMES = Map.of(
            "username", "用户名",
            "password", "密码",
            "nickname", "昵称",
            "email", "邮箱",
            "phone", "手机号"
    );

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserDtoMapper userDtoMapper;
    private final Validator validator;

    public AuthService(
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            UserDtoMapper userDtoMapper,
            Validator validator
    ) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userDtoMapper = userDtoMapper;
        this.validator = validator;
    }

    /**
     * 注册新用户。
     *
     * <p>应用层预检查用于返回明确提示，数据库唯一索引仍作为并发注册时的最终保障。</p>
     */
    @Transactional
    public UserInfo register(RegisterRequest request) {
        normalizeRegistrationRequest(request);
        validateRequest(request);
        validateBcryptPasswordLength(request.getPassword());

        ensureRegistrationFieldsAvailable(
                request.getUsername(),
                request.getEmail(),
                request.getPhone()
        );

        User user = User.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .nickname(request.getNickname())
                .email(request.getEmail())
                .phone(request.getPhone())
                .status("ACTIVE")
                .deleted(0)
                .build();

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            // 并发请求可能同时通过预检查，唯一索引冲突统一转换为稳定的 409 响应。
            throw conflict("注册信息已存在");
        }
        return userDtoMapper.toUserInfo(user);
    }

    /**
     * 校验登录凭据并签发 JWT。
     */
    @Transactional
    public LoginData login(LoginRequest request) {
        request.setUsername(normalizeCaseInsensitiveValue(request.getUsername()));
        validateRequest(request);
        validateBcryptPasswordLength(request.getPassword());
        User user = findByUsername(request.getUsername());

        /*
         * 用户不存在时仍执行一次固定 BCrypt 哈希比较，使未知用户和错误密码路径承担相近成本，
         * 避免攻击者仅凭响应耗时推测用户名是否已经注册。
         */
        String passwordHash = user == null ? DUMMY_PASSWORD_HASH : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), passwordHash);
        if (user == null || !passwordMatches) {
            throw new BusinessException(
                    ResponseCode.UNAUTHORIZED,
                    "用户名或密码错误",
                    HttpStatus.UNAUTHORIZED
            );
        }

        ensureUserCanAuthenticate(user);

        LocalDateTime lastLoginAt = LocalDateTime.now();
        int updatedRows = userMapper.updateLastLoginAtIfActive(user.getId(), lastLoginAt);
        if (updatedRows == 0) {
            /*
             * 密码校验后账号可能被管理员并发禁用、锁定或删除。条件更新失败时终止签发，
             * 同时避免用登录开始时读取的旧实体覆盖最新账号状态。
             */
            throw new BusinessException(
                    ResponseCode.FORBIDDEN,
                    "账号状态已变更，请重新登录",
                    HttpStatus.FORBIDDEN
            );
        }
        user.setLastLoginAt(lastLoginAt);
        JwtService.IssuedToken issuedToken = jwtService.createAccessToken(user);

        return new LoginData(
                issuedToken.value(),
                LoginData.TokenTypeEnum.BEARER,
                issuedToken.expiresInSeconds(),
                userDtoMapper.toUserInfo(user)
        );
    }

    /**
     * 根据已认证身份重新读取当前用户。
     */
    @Transactional(readOnly = true)
    public UserInfo getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(
                    ResponseCode.UNAUTHORIZED,
                    "登录状态无效或已过期，请重新登录",
                    HttpStatus.UNAUTHORIZED
            );
        }
        return userDtoMapper.toUserInfo(user);
    }

    private void ensureRegistrationFieldsAvailable(String username, String email, String phone) {
        if (userMapper.selectCount(new LambdaQueryWrapper<User>()
                .apply("LOWER(username) = LOWER({0})", username)) > 0) {
            throw conflict("用户名已被使用");
        }
        if (email != null && userMapper.selectCount(new LambdaQueryWrapper<User>()
                .apply("LOWER(email) = LOWER({0})", email)) > 0) {
            throw conflict("邮箱已被使用");
        }
        if (phone != null && userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, phone)) > 0) {
            throw conflict("手机号已被使用");
        }
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .apply("LOWER(username) = LOWER({0})", username));
    }

    private void ensureUserCanAuthenticate(User user) {
        if ("DISABLED".equals(user.getStatus())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "账号已被禁用", HttpStatus.FORBIDDEN);
        }
        if ("LOCKED".equals(user.getStatus())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "账号已被锁定", HttpStatus.FORBIDDEN);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "账号状态异常", HttpStatus.FORBIDDEN);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ResponseCode.CONFLICT, message, HttpStatus.CONFLICT);
    }

    private void normalizeRegistrationRequest(RegisterRequest request) {
        /*
         * 必须先规范化再校验，否则带空白的原始值可能先通过长度或格式约束，
         * 去除空白后却成为不符合契约的数据，或者绕过唯一性预检查。
         */
        /*
         * 用户名和邮箱按大小写不敏感规则参与唯一性判断，因此必须保存统一的小写规范值。
         * 这样并发请求即使同时通过应用层预检查，也会在数据库唯一索引处发生确定冲突。
         */
        request.setUsername(normalizeCaseInsensitiveValue(request.getUsername()));
        request.setNickname(trimToNull(request.getNickname()));
        request.setEmail(normalizeCaseInsensitiveValue(request.getEmail()));
        request.setPhone(trimToNull(request.getPhone()));
    }

    private <T> void validateRequest(T request) {
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (violations.isEmpty()) {
            return;
        }

        String invalidFields = violations.stream()
                .map(violation -> FIELD_NAMES.getOrDefault(
                        violation.getPropertyPath().toString(),
                        violation.getPropertyPath().toString()
                ))
                .distinct()
                .sorted()
                .collect(Collectors.joining("、"));
        String message = invalidFields.isBlank()
                ? "参数校验失败，请检查输入内容"
                : "参数校验失败，请检查" + invalidFields;
        throw new BusinessException(
                ResponseCode.VALIDATION_FAILED,
                message,
                HttpStatus.UNPROCESSABLE_ENTITY
        );
    }

    private void validateBcryptPasswordLength(String password) {
        /*
         * BCrypt 只接受最多 72 字节的原始密码。OpenAPI 的 maxLength 按字符计数，
         * 因此多字节字符仍需在运行时按 UTF-8 字节数校验，避免编码或比对阶段抛出 500。
         */
        if (password != null && password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(
                    ResponseCode.VALIDATION_FAILED,
                    "参数校验失败，请检查密码",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeCaseInsensitiveValue(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
