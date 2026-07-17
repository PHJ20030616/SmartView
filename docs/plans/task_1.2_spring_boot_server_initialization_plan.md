# Task 1.2 初始化 Spring Boot 工程执行计划

## 目标

在 `smartview-server/` 下初始化 Spring Boot 后端工程，提供可启动、可测试、可通过 Swagger UI 查看基础接口的服务骨架，并建立统一响应结构、TraceId 传递和全局异常处理。

> 前置门禁：本计划必须经过全新子 Agent 审查通过后才开始编码。若审查不通过，先修订本计划并重新审查。

## 技术选型依据

- Spring Boot：使用 Context7 查询到的 Spring Boot 3.5 文档，采用 `spring-boot-starter-parent` 管理 Web、Validation、Security、Redis、AMQP、Test 等依赖。
- Swagger/OpenAPI：使用 Context7 查询到的 springdoc 文档，引入 `springdoc-openapi-starter-webmvc-ui`，默认暴露 `/swagger-ui.html` 和 `/v3/api-docs`。
- MyBatis Plus：使用 Context7 查询到的 Spring Boot 3 starter，采用 `mybatis-plus-spring-boot3-starter`，并通过 `mybatis-plus-bom` 管理版本。
- JWT：使用 Context7 查询到的 JJWT 依赖拆分方式，引入 `jjwt-api`、`jjwt-impl`、`jjwt-jackson`。
- MinIO：引入 `io.minio:minio` Java SDK，先提供依赖和配置入口，业务存储能力留给后续任务实现。

## 文件结构

- Modify: `contracts/web-api/openapi.yaml`
- Create: `smartview-server/pom.xml`
- Create: `smartview-server/src/main/java/com/smartview/SmartViewServerApplication.java`
- Create: `smartview-server/src/main/java/com/smartview/common/api/ApiResponse.java`
- Create: `smartview-server/src/main/java/com/smartview/common/api/ResponseCode.java`
- Create: `smartview-server/src/main/java/com/smartview/common/api/TraceIdFilter.java`
- Create: `smartview-server/src/main/java/com/smartview/common/exception/BusinessException.java`
- Create: `smartview-server/src/main/java/com/smartview/common/exception/GlobalExceptionHandler.java`
- Create: `smartview-server/src/main/java/com/smartview/config/SecurityConfig.java`
- Create: `smartview-server/src/main/java/com/smartview/config/OpenApiConfig.java`
- Create: `smartview-server/src/main/java/com/smartview/config/MinioConfig.java`
- Create: `smartview-server/src/main/java/com/smartview/config/properties/MinioProperties.java`
- Create: `smartview-server/src/main/java/com/smartview/config/properties/JwtProperties.java`
- Create: `smartview-server/src/main/java/com/smartview/health/HealthController.java`
- Create: `smartview-server/src/main/resources/application.yml`
- Create: `smartview-server/src/test/java/com/smartview/SmartViewServerApplicationTests.java`
- Create: `smartview-server/src/test/java/com/smartview/health/HealthControllerTest.java`
- Create when needed: `docs/errors/task_1.2_spring_boot_server_initialization_plan_errors.md`

## 实施步骤

- [ ] Step 1: 先更新 Web API 契约
  - 修改 `contracts/web-api/openapi.yaml`，确保 `/api/health` 的 200 响应使用统一响应结构。
  - 将健康响应建模为 `ApiResponse` 包装的 `data` 对象，`data` 至少包含 `status`、`service`、`timestamp`。
  - 确认错误响应结构包含 `code`、`message`、`traceId`，并保留契约已有 `timestamp` 字段。
  - 本任务只初始化后端模块，不生成前端 Client；后续前端任务应从该契约生成类型和调用代码。

- [ ] Step 2: 创建 Maven 工程骨架和依赖
  - 使用 Java 21、Spring Boot 3.5.x。
  - 引入 Web、Validation、Security、MyBatis Plus、MySQL Driver、Redis、RabbitMQ、MinIO、springdoc OpenAPI、Lombok、JJWT、Test。
  - 通过 Spring Boot parent 和 MyBatis Plus BOM 管理可管理依赖版本，显式锁定第三方 SDK 版本。
  - `smartview-server` 是本任务指定的 Spring Boot 后端模块名称；它承接架构文档中 Spring Boot 后端职责，后续文档如仍出现 `smartview-backend`，以用户本任务指定名称为准。

- [ ] Step 3: 创建应用入口与配置文件
  - `SmartViewServerApplication` 启用配置属性绑定。
  - `application.yml` 定义服务端口、应用名、数据源、Redis、RabbitMQ、MinIO、JWT、springdoc 路径。
  - 默认配置使用本地开发环境变量占位，避免硬编码真实敏感信息。
  - 基础测试使用 `test` profile 禁用或延迟会触发外部连接的自动配置，确保未启动 MySQL、Redis、RabbitMQ、MinIO 时也能完成上下文加载和接口测试。

- [ ] Step 4: 建立统一响应和 TraceId
  - `ApiResponse<T>` 固定包含 `code`、`message`、`data`、`traceId`、`timestamp`，与契约保持一致。
  - 本任务中的 `ApiResponse<T>` 是后端运行时响应封装，不作为前端手写 DTO 传播；跨端类型仍以 `contracts/web-api/openapi.yaml` 生成结果为准。
  - `TraceIdFilter` 从 `X-Trace-Id` 读取或生成 TraceId，写入响应头和 MDC。
  - 提供 `ResponseCode` 枚举承载基础业务码。

- [ ] Step 5: 建立全局异常处理
  - `GlobalExceptionHandler` 处理业务异常、参数校验异常、约束异常和兜底异常。
  - 所有面向用户的错误消息使用中文。
  - 对参数校验错误做聚合输出，保留 TraceId 便于日志定位。

- [ ] Step 6: 配置基础安全与 Swagger
  - `SecurityConfig` 暂时禁用 CSRF、表单登录和 HTTP Basic，放行 `/api/health`、Swagger UI、OpenAPI 文档路径。
  - 其它接口默认要求认证，为后续 JWT 鉴权任务保留边界。
  - 配置 `AuthenticationEntryPoint` 和 `AccessDeniedHandler`，确保 401/403 也返回统一响应结构和中文错误消息。
  - `OpenApiConfig` 提供基础 OpenAPI 元信息。

- [ ] Step 7: 实现 `/api/health`
  - `HealthController` 返回统一响应，数据包含 `status`、`service`、`timestamp`。
  - 该接口不依赖数据库、中间件或 AI 服务，确保服务骨架可独立启动验收。

- [ ] Step 8: 编写基础测试
  - 应用上下文加载测试。
  - MockMvc 验证 `/api/health` 返回 HTTP 200，并包含 `code`、`message`、`data`、`traceId`。
  - MockMvc 验证 `/v3/api-docs` 可访问并包含健康接口路径。
  - MockMvc 验证 Swagger UI 入口 `/swagger-ui.html` 可访问或返回到 UI 资源的 3xx 跳转。
  - 覆盖 `BusinessException`、请求参数校验异常、兜底异常响应，断言中文错误消息和 `traceId`。
  - 覆盖未认证访问受保护测试接口时返回统一 401 响应。

- [ ] Step 9: 执行验证
  - 运行 `mvn test`。
  - 运行 `mvn spring-boot:run` 启动服务。
  - 请求 `http://localhost:8080/api/health` 验证统一响应。
  - 请求 `http://localhost:8080/v3/api-docs` 验证 OpenAPI 文档包含健康接口。
  - 请求 `http://localhost:8080/swagger-ui.html` 验证 Swagger UI 可访问。

- [ ] Step 10: 子 Agent 代码审查与修复
  - 实现后调用全新子 Agent 审查本任务改动。
  - 如果发现问题，先创建 `docs/errors/task_1.2_spring_boot_server_initialization_plan_errors.md` 修复计划，再执行修复。
  - 修复后重新运行相关测试和验收命令。

- [ ] Step 11: 提交并同步远程
  - 只暂存本任务新增/修改文件。
  - 提交备注使用简短中文或英文说明。
  - 检查并补齐 GitHub、Gitee remote，然后推送到两个远程仓库。

## 验收标准映射

- Spring Boot 能启动：通过 `mvn spring-boot:run` 验证。
- `/api/health` 返回成功：通过 MockMvc 和本地 HTTP 请求验证。
- Swagger UI 能展示接口：通过 `/swagger-ui.html` 和 `/v3/api-docs` 验证。
- 统一响应包含 `code`、`message`、`data`、`traceId`：通过契约和测试断言。
- 基础测试可运行：通过 `mvn test` 验证。

## 风险与边界

- 本任务只初始化后端工程骨架，不实现账号、JWT 登录、数据库表、Mapper 或业务 API。
- `application.yml` 默认连接本地基础设施，但健康接口和基础测试不依赖这些服务实际运行。
- Swagger UI 受 Spring Security 放行保护，但后续业务接口仍应默认认证。
