package com.smartview.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartview.config.properties.JwtProperties;
import com.smartview.generated.web.model.LoginRequest;
import com.smartview.generated.web.model.RegisterRequest;
import com.smartview.user.entity.User;
import com.smartview.user.mapper.UserMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    private static final String USERNAME = "smartuser";
    private static final String PASSWORD = "Secret123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeEach
    void cleanDatabase() {
        // 物理清理测试数据，避免 user 表的用户名唯一索引与逻辑删除记录相互影响。
        jdbcTemplate.execute("DELETE FROM `user`");
    }

    @Test
    void registerShouldPersistUserWithBcryptPassword() throws Exception {
        RegisterRequest request = registerRequest(USERNAME, "用户一", "user@example.com", "13800138000");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(jsonPath("$.data.nickname").value("用户一"))
                .andExpect(jsonPath("$.data.email").value("user@example.com"))
                .andExpect(jsonPath("$.data.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());

        User persisted = findByUsername(USERNAME);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, persisted.getPasswordHash())).isTrue();
    }

    @Test
    void registerShouldRejectDuplicateUsernameEmailAndPhone() throws Exception {
        register(registerRequest(USERNAME, "用户一", "user@example.com", "13800138000"));

        assertConflict(registerRequest(USERNAME, "用户二", "other@example.com", "13900139000"), "用户名已被使用");
        assertConflict(registerRequest("otheruser", "用户二", "user@example.com", "13900139000"), "邮箱已被使用");
        assertConflict(registerRequest("thirduser", "用户三", "third@example.com", "13800138000"), "手机号已被使用");
    }

    @Test
    void registerShouldNormalizeFieldsBeforeValidationAndPersistence() throws Exception {
        RegisterRequest request = registerRequest(
                " " + USERNAME + " ",
                " 用户一 ",
                " user@example.com ",
                " 13800138000 "
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(jsonPath("$.data.nickname").value("用户一"))
                .andExpect(jsonPath("$.data.email").value("user@example.com"))
                .andExpect(jsonPath("$.data.phone").value("13800138000"));

        User persisted = findByUsername(USERNAME);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getNickname()).isEqualTo("用户一");
        assertThat(persisted.getEmail()).isEqualTo("user@example.com");
        assertThat(persisted.getPhone()).isEqualTo("13800138000");
    }

    @Test
    void registerShouldValidateNormalizedUsernameAndNickname() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registerRequest(" ab ", "用户一", null, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registerRequest("valid-user", "   ", null, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(userMapper.selectCount(null)).isZero();
    }

    @Test
    void registerShouldRejectEmailLongerThanDatabaseColumn() throws Exception {
        // 本地部分与域名均保持合法格式，仅让总长度超过数据库 varchar(100) 上限。
        String overlongEmail = "a".repeat(60) + "@" + "b".repeat(36) + ".com";
        assertThat(overlongEmail).hasSize(101);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registerRequest(USERNAME, "用户一", overlongEmail, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(userMapper.selectCount(null)).isZero();
    }

    @Test
    void registerShouldRejectPasswordLongerThanBcryptByteLimit() throws Exception {
        String overlongPassword = "密".repeat(25);
        assertThat(overlongPassword.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        RegisterRequest request = new RegisterRequest(USERNAME, overlongPassword, "用户一");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("参数校验失败，请检查密码"));

        assertThat(userMapper.selectCount(null)).isZero();
    }

    @Test
    void registerShouldReturnBadRequestForEmptyBody() throws Exception {
        assertUnreadableBodyReturnsBadRequest("/api/auth/register", "");
    }

    @Test
    void registerShouldReturnBadRequestForMalformedJson() throws Exception {
        assertUnreadableBodyReturnsBadRequest("/api/auth/register", "{");
    }

    @Test
    void registerShouldRejectCaseAndWhitespaceVariants() throws Exception {
        register(registerRequest(USERNAME, "用户一", "user@example.com", "13800138000"));

        assertConflict(
                registerRequest(USERNAME.toUpperCase(), "用户二", "other@example.com", "13900139000"),
                "用户名已被使用"
        );
        assertConflict(
                registerRequest(" " + USERNAME + " ", "用户三", "third@example.com", "13700137000"),
                "用户名已被使用"
        );
        assertConflict(
                registerRequest("another-user", "用户四", " USER@example.com ", "13600136000"),
                "邮箱已被使用"
        );
    }

    @Test
    void concurrentRegistrationShouldLeaveOneUserAndReturnOneConflict() throws Exception {
        RegisterRequest firstRequest = registerRequest(USERNAME, "用户一", null, null);
        RegisterRequest secondRequest = registerRequest(USERNAME, "用户二", null, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> firstStatus = executor.submit(
                    () -> performConcurrentRegistration(firstRequest, ready, start)
            );
            Future<Integer> secondStatus = executor.submit(
                    () -> performConcurrentRegistration(secondRequest, ready, start)
            );

            ready.await();
            start.countDown();

            assertThat(List.of(firstStatus.get(), secondStatus.get()))
                    .containsExactlyInAnyOrder(200, 409);
        }

        assertThat(userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, USERNAME))).isEqualTo(1);
    }

    @Test
    void concurrentCaseVariantRegistrationShouldLeaveOneNormalizedUser() throws Exception {
        RegisterRequest firstRequest = registerRequest(USERNAME, "用户一", null, null);
        RegisterRequest secondRequest = registerRequest(USERNAME.toUpperCase(), "用户二", null, null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> firstStatus = executor.submit(
                    () -> performConcurrentRegistration(firstRequest, ready, start)
            );
            Future<Integer> secondStatus = executor.submit(
                    () -> performConcurrentRegistration(secondRequest, ready, start)
            );

            ready.await();
            start.countDown();

            assertThat(List.of(firstStatus.get(), secondStatus.get()))
                    .containsExactlyInAnyOrder(200, 409);
        }

        assertThat(userMapper.selectCount(null)).isEqualTo(1);
        assertThat(findByUsername(USERNAME)).isNotNull();
    }

    @Test
    void loginShouldReturnJwtAndCurrentUserShouldBeAccessible() throws Exception {
        register(registerRequest(USERNAME, "用户一", "user@example.com", "13800138000"));

        String token = loginAndGetToken(USERNAME, PASSWORD);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(jsonPath("$.data.lastLoginAt").exists())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void loginShouldReturnBadRequestForEmptyBody() throws Exception {
        assertUnreadableBodyReturnsBadRequest("/api/auth/login", "");
    }

    @Test
    void loginShouldReturnBadRequestForMalformedJson() throws Exception {
        assertUnreadableBodyReturnsBadRequest("/api/auth/login", "{");
    }

    @Test
    void loginShouldRejectPasswordLongerThanBcryptByteLimit() throws Exception {
        String overlongPassword = "密".repeat(25);
        assertThat(overlongPassword.getBytes(StandardCharsets.UTF_8)).hasSize(75);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(USERNAME, overlongPassword))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("参数校验失败，请检查密码"));
    }

    @Test
    void unknownUserAndWrongPasswordShouldReturnSameUnauthorizedResponse() throws Exception {
        register(registerRequest(USERNAME, "用户一", null, null));

        assertInvalidCredentials("missing-user", PASSWORD);
        assertInvalidCredentials(USERNAME, "WrongPassword");
    }

    @Test
    void protectedEndpointShouldRejectMissingInvalidAndExpiredAuthentication() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").value(nullValue()));

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("登录状态无效或已过期，请重新登录"));

        register(registerRequest(USERNAME, "用户一", null, null));
        User user = findByUsername(USERNAME);
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + createExpiredToken(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("登录状态无效或已过期，请重新登录"));
    }

    @Test
    void publicEndpointsShouldIgnoreInvalidJwt() throws Exception {
        mockMvc.perform(get("/api/health")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(get("/v3/api-docs")
                        .header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isOk());
    }

    @Test
    void authEndpointsShouldIgnoreInvalidJwt() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .header("Authorization", "Bearer invalid.jwt.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registerRequest(USERNAME, "用户一", null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(post("/api/auth/login")
                        .header("Authorization", "Bearer invalid.jwt.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(USERNAME, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void existingTokenShouldStopWorkingWhenUserBecomesUnavailable() throws Exception {
        register(registerRequest(USERNAME, "用户一", null, null));
        String disabledToken = loginAndGetToken(USERNAME, PASSWORD);
        User user = findByUsername(USERNAME);
        user.setStatus("DISABLED");
        userMapper.updateById(user);

        assertTokenRejected(disabledToken);

        user.setStatus("ACTIVE");
        userMapper.updateById(user);
        String deletedToken = loginAndGetToken(USERNAME, PASSWORD);
        userMapper.deleteById(user.getId());

        assertTokenRejected(deletedToken);
    }

    @Test
    void disabledAndLockedUsersShouldNotBeAbleToLogin() throws Exception {
        insertUser("disabled-user", "DISABLED");
        insertUser("locked-user", "LOCKED");

        assertLoginForbidden("disabled-user", "账号已被禁用");
        assertLoginForbidden("locked-user", "账号已被锁定");
    }

    private RegisterRequest registerRequest(
            String username,
            String nickname,
            String email,
            String phone
    ) {
        return new RegisterRequest(username, PASSWORD, nickname)
                .email(email)
                .phone(phone);
    }

    private void register(RegisterRequest request) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private void assertConflict(RegisterRequest request, String message) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(greaterThan(0)))
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data")
                .path("token")
                .asText();
    }

    private void assertInvalidCredentials(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    private void assertTokenRejected(String token) throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void assertLoginForbidden(String username, String message) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private void assertUnreadableBodyReturnsBadRequest(String path, String content) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.message").value("请求体格式错误，请检查后重试"));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
    }

    private void insertUser(String username, String status) {
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .nickname(username)
                .status(status)
                .lastLoginAt(LocalDateTime.now().minusDays(1))
                .deleted(0)
                .build();
        userMapper.insert(user);
    }

    private int performConcurrentRegistration(
            RegisterRequest request,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        return mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String createExpiredToken(User user) {
        SecretKey key = Keys.hmacShaKeyFor(
                jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8)
        );
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key)
                .compact();
    }
}
