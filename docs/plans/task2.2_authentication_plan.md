# Task 2.2 - 注册与登录接口实施计划

**日期：** 2026-07-22  
**状态：** 已完成（最终独立复审通过）
**范围：** `smartview-server`、`contracts/web-api`

---

## 1. 任务目标

本任务实现 SmartView 的基础身份认证能力：

1. `POST /api/auth/register`：创建用户并返回脱敏后的用户信息。
2. `POST /api/auth/login`：校验用户名和密码，返回 JWT 与当前用户信息。
3. `GET /api/users/me`：根据当前有效 JWT 返回用户信息。
4. 用户密码必须使用 BCrypt 单向哈希保存，任何接口不得返回密码或密码哈希。
5. 受保护接口在缺少、伪造、过期或不可用 JWT 时统一返回 HTTP 401。

注册成功后不自动登录，也不签发 JWT。只有登录成功才签发 JWT。

---

## 2. 已确认的业务规则

### 2.1 注册规则

- 用户名、密码为必填字段，其余字段遵循现有 OpenAPI 契约定义。
- 注册前检查用户名、邮箱和手机号是否已被使用。
- 用户名、邮箱或手机号冲突时返回 HTTP 409，并使用中文错误信息。
- 密码通过 Spring Security `PasswordEncoder` 的 BCrypt 实现加密后写入 `password_hash`。
- 新用户默认状态为 `ACTIVE`。
- 注册响应只返回脱敏用户信息，不返回 JWT、原始密码或密码哈希。
- 应用层预检查用于提供明确错误信息，数据库唯一约束仍作为并发注册时的最终保障。

### 2.2 登录规则

- 使用用户名和密码登录。
- 用户名不存在或密码错误时统一返回相同的中文错误信息，避免泄露账号是否存在。
- 仅 `ACTIVE` 用户允许登录；`DISABLED`、`LOCKED`、已软删除或不存在的用户均不得登录。
- 登录成功后更新 `last_login_at`，并返回 JWT 与脱敏用户信息。
- JWT 使用用户 ID 作为 `sub`，同时记录用户名、签发者、签发时间和过期时间。

### 2.3 当前用户规则

- `/api/users/me` 必须经过 JWT 认证。
- JWT 验签成功后仍需根据用户 ID 查询数据库，确保被禁用、锁定、软删除或已经不存在的用户立即失去访问权限。
- 返回内容与注册响应中的用户信息模型保持一致。

### 2.4 认证失败规则

以下情况统一交由安全层返回 HTTP 401：

- 未携带 `Authorization` 请求头。
- 请求头不是 `Bearer <token>` 格式。
- JWT 签名无效、格式错误或已经过期。
- JWT 中缺少合法用户 ID。
- JWT 对应用户不存在、已软删除、被禁用或被锁定。

认证失败响应继续使用项目现有统一响应结构和中文提示，不在日志或响应中暴露 JWT 密钥、完整令牌或密码。

---

## 3. 契约优先实施

业务代码修改前，先更新 `contracts/web-api/openapi.yaml`，再由 Maven 在构建阶段生成 DTO。

### 3.1 契约调整

1. 将注册成功响应从通用 `ApiResponse` 改为复用现有 `UserResponse`，其 `data` 类型为 `UserInfo`。
2. 为注册接口补充 HTTP 409 冲突响应。
3. 将登录成功响应中的内联 `data` 对象提取为命名模型 `LoginData`。
4. 明确 `LoginData` 包含：
   - `token`
   - `tokenType`
   - `expiresIn`
   - `user`
5. 检查 `UserInfo.status` 枚举，使其与用户实体的 `ACTIVE`、`DISABLED`、`LOCKED` 状态一致。
6. 确认三个接口的 400、401、409 响应均引用项目统一错误响应结构。

### 3.2 DTO 生成

在 `smartview-server/pom.xml` 配置 OpenAPI Generator Maven 插件，仅生成本任务使用的模型：

- `RegisterRequest`
- `LoginRequest`
- `UserInfo`
- `LoginData`

生成代码放在 Maven 构建目录：

`smartview-server/target/generated-sources/openapi`

Java 包名计划使用：

`com.smartview.generated.web.model`

生成目录不纳入手工维护，不直接修改生成文件。业务代码只引用生成模型，通过独立映射器完成 `User` 实体到 `UserInfo` 的转换。

### 3.3 契约一致性检查

- 执行 OpenAPI 生成目标，确认模型可以生成和编译。
- 检查 Controller 的请求、响应模型均来自生成包。
- 检查业务代码没有新增与契约重复的手写跨端 DTO。

---

## 4. 安全设计

### 4.1 密码处理

- 在 `SecurityConfig` 中提供 `BCryptPasswordEncoder` 对应的 `PasswordEncoder` Bean。
- 注册时只保存 `passwordEncoder.encode(rawPassword)` 的结果。
- 登录时使用 `passwordEncoder.matches(rawPassword, passwordHash)` 校验。
- 不实现密码解密逻辑，也不在任何日志中记录密码。

### 4.2 JWT 配置

扩展现有 `JwtProperties` 和 `application.yml`，使用以下配置项：

- `secret`：签名密钥，从环境变量读取，禁止在代码中硬编码生产密钥。
- `issuer`：令牌签发者。
- `expiration`：令牌有效期。

测试环境使用独立且长度满足算法要求的固定密钥；生产配置继续允许通过环境变量覆盖。

### 4.3 JWT 服务

新增 `JwtService`，负责：

- 根据用户信息签发 JWT。
- 验证签名、签发者和过期时间。
- 从 `sub` 安全解析用户 ID。
- 将 JJWT 的过期、签名、格式和参数异常转换为认证失败，不把底层异常直接返回给客户端。

JWT 不保存密码、密码哈希、手机号或邮箱等不必要敏感信息。

### 4.4 认证过滤器

新增 `JwtAuthenticationFilter`，继承 `OncePerRequestFilter`：

1. 读取并校验 Bearer 请求头。
2. 验证 JWT 并解析用户 ID。
3. 查询当前用户数据库记录。
4. 验证用户仍为 `ACTIVE`。
5. 构造 `AuthenticatedUser`，写入 `SecurityContext`。
6. 认证失败时清理上下文并调用统一 401 处理器。

过滤器不对公开接口强制要求令牌；公开接口即使没有令牌也可继续执行。

### 4.5 Spring Security 配置

修改 `SecurityConfig`：

- 保持无状态会话策略。
- `POST /api/auth/register` 与 `POST /api/auth/login` 设置为公开。
- `/api/users/me` 及其他未明确公开的接口保持受保护。
- 将 JWT 过滤器放在用户名密码认证过滤器之前。
- 继续使用现有 `AuthenticationEntryPoint` 输出统一 HTTP 401 响应。
- 不启用服务端 Session，也不使用表单登录。

---

## 5. 代码结构与职责

| 文件 | 操作 | 职责 |
| --- | --- | --- |
| `contracts/web-api/openapi.yaml` | 修改 | 先定义注册、登录、当前用户接口及 DTO 契约 |
| `smartview-server/pom.xml` | 修改 | 配置 OpenAPI DTO 生成与 H2 测试依赖 |
| `smartview-server/src/main/resources/application.yml` | 修改 | 补充 JWT 签发者、有效期和环境变量配置 |
| `smartview-server/src/main/java/com/smartview/security/JwtProperties.java` | 修改 | 承载并校验 JWT 配置 |
| `smartview-server/src/main/java/com/smartview/security/JwtService.java` | 新增 | JWT 签发、验签和用户 ID 解析 |
| `smartview-server/src/main/java/com/smartview/security/AuthenticatedUser.java` | 新增 | 保存当前认证用户的最小安全上下文 |
| `smartview-server/src/main/java/com/smartview/security/JwtAuthenticationFilter.java` | 新增 | Bearer JWT 认证与数据库用户状态复核 |
| `smartview-server/src/main/java/com/smartview/security/SecurityConfig.java` | 修改 | 配置公开路径、BCrypt、JWT 过滤器和 401 入口 |
| `smartview-server/src/main/java/com/smartview/common/response/ResponseCode.java` | 修改 | 补充认证失败和资源冲突所需响应码 |
| `smartview-server/src/main/java/com/smartview/user/service/AuthService.java` | 新增 | 注册、登录、用户唯一性和状态校验 |
| `smartview-server/src/main/java/com/smartview/user/controller/AuthController.java` | 新增 | 提供注册与登录接口 |
| `smartview-server/src/main/java/com/smartview/user/controller/UserController.java` | 新增 | 提供 `/api/users/me` |
| `smartview-server/src/main/java/com/smartview/user/dto/UserDtoMapper.java` | 新增 | 将用户实体映射为生成的脱敏 `UserInfo` |
| `smartview-server/src/test/resources/application-test.yml` | 新增 | H2、Flyway 和测试 JWT 配置 |
| `smartview-server/src/test/resources/db/migration/V1__create_user_table.sql` | 视兼容性决定 | 仅在现有 MySQL 迁移无法直接运行于 H2 时提供等价测试表结构 |
| `smartview-server/src/test/java/com/smartview/security/JwtServiceTest.java` | 新增 | JWT 签发、解析、篡改和过期测试 |
| `smartview-server/src/test/java/com/smartview/user/AuthApiIntegrationTest.java` | 新增 | 注册、登录、当前用户和 401 集成测试 |

具体文件名可根据现有包结构做小幅调整，但不得改变契约优先、生成 DTO 和统一安全入口三项原则。

---

## 6. 中文注释与中文文案检查

实现时必须在以下非直观位置添加准确的中文注释：

- 为什么 JWT 验签后仍要查询数据库用户状态。
- 为什么用户名不存在和密码错误使用相同提示。
- 为什么应用层唯一性检查后仍要捕获数据库唯一约束异常。
- JWT 过滤器中公开请求、无令牌请求和无效令牌请求的处理边界。
- 测试环境与生产环境 JWT 密钥配置的隔离方式。

所有面向调用方的成功、失败和校验提示使用中文。注释只解释安全约束、边界和实现取舍，不机械复述代码。

---

## 7. 测试驱动实施步骤

### 7.1 第一阶段：契约和生成代码

1. 修改 OpenAPI 契约。
2. 配置 OpenAPI Generator。
3. 执行生成和编译，确认生成模型可被后端引用。

计划命令：

```powershell
mvn generate-sources -DskipTests
mvn compile -DskipTests
```

执行目录：`smartview-server`

### 7.2 第二阶段：先编写失败测试

先增加 JWT 单元测试和认证接口集成测试，至少覆盖：

1. 注册成功并真实写入数据库。
2. 数据库存储值不等于明文密码，且 BCrypt 可以匹配原密码。
3. 重复用户名返回 409。
4. 重复邮箱返回 409。
5. 重复手机号返回 409。
6. 正确用户名和密码登录成功并返回 JWT。
7. 用户名不存在与密码错误均返回统一错误。
8. 禁用或锁定用户无法登录。
9. 有效 JWT 可以访问 `/api/users/me`。
10. 未携带 JWT 访问 `/api/users/me` 返回 401。
11. 伪造、过期、格式错误的 JWT 返回 401。
12. JWT 签发后用户被禁用、锁定、软删除或删除时返回 401。
13. 注册和用户信息响应不包含密码或密码哈希。

先运行测试并确认因实现缺失而失败，保留红灯结果摘要。

计划命令：

```powershell
mvn -Dtest=JwtServiceTest,AuthApiIntegrationTest test
```

### 7.3 第三阶段：最小实现通过测试

按以下顺序实现：

1. BCrypt Bean、JWT 配置和 `JwtService`。
2. 生成 DTO 的实体映射器。
3. `AuthService` 注册、登录和唯一性校验。
4. 注册、登录 Controller。
5. JWT 过滤器和安全链配置。
6. 当前用户 Controller。
7. 补全异常映射、中文提示和必要中文注释。

每完成一段即运行对应测试，避免一次性堆积问题。

### 7.4 第四阶段：完整回归

计划命令：

```powershell
mvn clean test
```

回归检查：

- 所有单元测试和集成测试通过。
- OpenAPI 生成在干净构建中可重复执行。
- H2 使用 MySQL 兼容模式验证真实数据库写入和唯一约束。
- 不依赖本机 Docker 或外部 MySQL 才能运行认证测试。
- 构建输出中不包含明文密码、JWT 密钥或完整令牌。

---

## 8. 验收标准与测试证据

### 8.1 功能验收

| 验收项 | 验证方式 |
| --- | --- |
| 注册成功后数据库有用户记录 | H2 集成测试直接查询 `user` 表或通过 `UserMapper` 查询 |
| 密码字段不是明文 | 断言数据库值不等于原密码，并用 BCrypt `matches` 验证 |
| 登录成功返回 JWT | 断言响应包含非空 token，并由 `JwtService` 成功解析 |
| 当前用户接口返回正确用户 | 携带有效 JWT 请求 `/api/users/me` 并核对用户字段 |
| 未登录访问受保护接口返回 401 | 不带请求头调用 `/api/users/me` |
| 无效 JWT 返回 401 | 分别测试伪造、过期和格式错误令牌 |
| 不可用用户无法继续访问 | 签发令牌后修改用户状态或删除用户，再请求受保护接口 |

### 8.2 完成时必须报告

- 实际执行的测试命令。
- 单元测试类、用例数量和结果。
- 集成测试覆盖的接口与错误场景。
- 总测试通过数、失败数和通过率。
- 如有未执行或失败测试，明确说明原因，不以“应该可用”代替证据。

---

## 9. 代码审查与问题修复流程

全部测试通过后，必须启动一个全新的子 Agent 审查本次代码，重点检查：

- JWT 是否存在签名、过期、身份冒用或敏感信息泄露风险。
- Spring Security 过滤器顺序和 401 行为是否正确。
- 用户状态、软删除和并发唯一性处理是否完整。
- DTO 是否完全遵守契约生成规则。
- 响应是否意外暴露密码字段。
- 测试是否遗漏关键边界。
- 必要位置是否存在准确的中文注释。

若审查发现问题：

1. 创建 `docs/errors/task2.2_authentication_plan_errors.md`。
2. 记录问题、影响、根因、修复方案和验证方法。
3. 用户确认修复计划后再实施修复。
4. 修复完成后重新测试并由新的审查过程验证。
5. 若再次发现问题，追加到同一错误计划文档，不覆盖原记录。

---

## 10. Git 提交与远程同步

### 10.1 提交边界

- 不修改、不暂存、不提交用户现有的 `.gitignore` 变更。
- 实施阶段只提交本任务相关契约、代码、测试和文档。
- 提交前检查是否包含密钥、令牌、数据库密码、构建产物或生成目录。
- OpenAPI 生成代码位于 `target`，不提交到仓库。

### 10.2 提交流程

1. 计划文档确认前，仅提交本计划文件。
2. 实现、测试和全新子 Agent 审查完成后，再提交业务代码。
3. 自动推送到：
   - `github`：`git@github.com:PHJ20030616/SmartView.git`
   - `gitee`：`https://gitee.com/phj20030616/smart-view.git`
4. 推送前后检查两个远程分支状态，确保提交一致。

---

## 11. 实施顺序总览

1. 用户审查并确认本计划。
2. 修改 OpenAPI 契约。
3. 配置并验证 DTO 生成。
4. 编写失败测试并确认红灯。
5. 实现 BCrypt、JWT 和认证过滤链。
6. 实现注册、登录、当前用户业务。
7. 运行定向测试与完整回归测试。
8. 检查必要中文注释、中文提示和敏感信息。
9. 启动全新子 Agent 代码审查。
10. 如有问题，按 `docs/errors` 修复计划流程处理。
11. 提交实现并推送 GitHub、Gitee 两个远程仓库。

---

## 12. 用户确认项

已确认：

- 注册成功返回脱敏用户信息，不自动登录，不返回 JWT。
- 登录成功返回 JWT 和用户信息。
- JWT 每次认证都重新查询数据库用户状态。
- 重复用户名、邮箱或手机号返回 HTTP 409。
- 认证和校验提示使用中文。
- 使用 H2 MySQL 兼容模式完成不依赖 Docker 的持久化集成测试。

已确认：

- 同意按照本计划修改契约、测试和业务代码。

---

## 13. 实际执行与验证记录

### 13.1 核心实现

- 已完成注册、登录和当前用户接口。
- 密码仅以 BCrypt 哈希写入数据库，响应中不返回密码或密码哈希。
- 登录成功签发 JWT，受保护接口缺少或携带无效 JWT 时返回统一 HTTP 401。
- JWT 验证通过后仍重新读取数据库用户状态，已禁用、锁定、软删除或不存在的用户无法继续访问。
- 用户名和邮箱在校验及持久化前统一规范为去除首尾空白的小写值，使数据库唯一索引能够稳定拦截并发大小写变体。
- 请求体缺失或 JSON 畸形时返回统一 HTTP 400 中文错误响应，不暴露底层解析细节。
- 登录成功只通过带 `ACTIVE` 和未删除条件的原子 SQL 更新最近登录时间，避免旧实体覆盖管理员并发修改的账号状态。
- 注册和登录均按 UTF-8 字节数校验 BCrypt 的 72 字节输入上限，超限稳定返回 HTTP 422。

### 13.2 第二轮修复测试

定向红灯命令：

```powershell
mvn "-Dtest=AuthApiIntegrationTest,GlobalExceptionHandlerTest" test
```

红灯结果：25 个测试中 6 个按预期失败；5 个请求体不可读场景错误返回 500，
并发注册 `smartuser` 与 `SMARTUSER` 时两个请求均成功，测试准确暴露了待修复问题。

修复后执行相同命令：25/25 通过，失败 0，错误 0，跳过 0。

### 13.3 完整回归

由于本机 Maven 3.6.1 无法使用默认 clean 插件版本，使用明确版本执行干净构建：

```powershell
mvn org.apache.maven.plugins:maven-clean-plugin:3.3.2:clean test
```

执行结果：

- OpenAPI Generator 7.14.0 从契约重新生成 `RegisterRequest`、`LoginRequest`、`LoginData` 和 `UserInfo`。
- 生成的 `RegisterRequest.email` 包含 `@Size(max = 100)`。
- 单元测试与集成测试共 39 个，39/39 通过。
- 失败 0，错误 0，跳过 0，通过率 100%。

最终状态将在全新独立审查通过并完成双远程推送后更新为“已完成”。

### 13.4 第三轮安全修复

最终复审发现并修复两个安全边界：

1. 登录流程不再使用 `updateById` 写回登录开始时读取的完整用户实体，而是通过账号状态条件更新
   `last_login_at`；更新行数为 0 时返回 HTTP 403，且不签发 JWT。
2. OpenAPI 将注册、登录密码字符上限调整为 72；服务层额外按 UTF-8 字节数校验，
   覆盖少量多字节字符超过 BCrypt 72 字节限制的情况。

测试驱动记录：

```powershell
mvn "-Dtest=AuthServiceTest,AuthApiIntegrationTest" test
```

- 红灯：测试编译因缺少 `updateLastLoginAtIfActive` 失败，准确暴露原子条件更新能力缺失。
- 绿灯：23/23 通过，失败 0，错误 0，跳过 0。
- 干净全量回归：39/39 通过，失败 0，错误 0，跳过 0。

### 13.5 第四轮过滤器异常边界修复

独立审查发现 `JwtAuthenticationFilter` 原先把 `filterChain.doFilter` 包含在 JWT 异常捕获范围内，
可能将下游业务抛出的 `IllegalArgumentException` 错误改写为 HTTP 401。

修复内容：

- 新增 `JwtAuthenticationFilterTest`，验证有效 JWT 通过认证后，下游参数异常必须原样传播。
- JWT 异常捕获范围缩小到令牌解析过程，数据库和下游业务异常继续交给正常异常处理链。
- 在关键边界添加中文安全注释，说明不得将下游异常误判为 JWT 校验失败。
- 注册和登录密码字段在 OpenAPI 中标记为 `writeOnly: true`。

测试驱动记录：

```powershell
mvn "-Dtest=JwtAuthenticationFilterTest" test
```

- 红灯：1 个测试按预期失败，证明旧实现吞掉了下游异常并尝试返回 401。
- 绿灯：1/1 通过，失败 0，错误 0，跳过 0。

最终干净全量回归：

```powershell
mvn org.apache.maven.plugins:maven-clean-plugin:3.3.2:clean test
```

- OpenAPI Generator 7.14.0 从契约重新生成 4 个 DTO。
- 后端测试共 40 个，40/40 通过。
- 失败 0，错误 0，跳过 0，通过率 100%。

### 13.6 最终独立复审

全新独立审查 Agent 最终结论为 **APPROVE**：

- P0、P1、P2 问题均为 0。
- 已确认 JWT 过滤器不会将下游 `IllegalArgumentException` 改写为 401。
- 生成 DTO 的 `toString()` 仍包含密码字段被记录为 P3 非阻塞风险；当前业务代码不记录请求 DTO，
  后续应通过生成器模板统一脱敏。
- 邮箱和手机号软删除后二次复用属于既有迁移结构的非阻塞残余风险，不影响本任务验收。
