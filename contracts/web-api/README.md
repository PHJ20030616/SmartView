# Spring Boot Web API 契约

## 契约用途

本目录存放 React 前端与 Spring Boot 后端之间的 HTTP API 契约定义，采用 OpenAPI 3.0 规范。

## 文件说明

- `openapi.yaml` — Web API 契约主文件，定义所有前端可调用的接口

## 文件命名规范

- 主契约文件：`openapi.yaml`
- 如需拆分，按模块命名：`openapi-{module}.yaml`（例如：`openapi-auth.yaml`、`openapi-interview.yaml`）
- 公共定义：`components/schemas/`、`components/responses/`

## 代码生成命令

### 前端 TypeScript Client 生成

```bash
# 在 smartview-web 目录下执行
npx openapi-generator-cli generate \
  -i ../contracts/web-api/openapi.yaml \
  -g typescript-axios \
  -o src/generated/api
```

### Spring Boot 服务端验证

```bash
# 在 smartview-backend 目录下执行
# 使用 openapi-generator-maven-plugin 或 springdoc-openapi
mvn clean compile
```

## 契约变更流程

### 1. 修改契约

在 `openapi.yaml` 中添加或修改接口定义：

```yaml
paths:
  /api/users/{id}:
    get:
      summary: 获取用户信息
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
            format: int64
      responses:
        '200':
          description: 成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/UserDTO'
```

### 2. 生成代码

```bash
# 生成前端 Client
cd smartview-web
npm run generate:api

# 验证后端代码
cd smartview-backend
mvn clean compile
```

### 3. 实现业务逻辑

- 前端：使用生成的 API Client 调用接口
- 后端：实现 Controller 逻辑，确保响应符合契约定义

### 4. 运行测试

```bash
# 契约测试（推荐使用 Pact 或 Spring Cloud Contract）
mvn test

# 集成测试
npm run test:integration
```

### 5. 提交代码

- 契约文件变更与代码实现在同一个 PR 中提交
- PR 描述中说明接口变更内容和影响范围

## 注意事项

1. **禁止绕过契约** — 前端不得手写与后端接口对应的类型定义
2. **先改契约，再写代码** — 接口变更必须先修改 `openapi.yaml`
3. **保持向后兼容** — 修改已有接口时注意版本管理和兼容性
4. **统一响应格式** — 所有接口使用统一的响应包装结构（`CommonResponse<T>`）
5. **错误码规范** — 使用标准 HTTP 状态码 + 业务错误码

## 常见问题

### Q: 契约文件修改后，前端类型没有更新？

A: 需要重新运行 `npm run generate:api` 生成新的 Client 代码。

### Q: 后端接口实现与契约不一致怎么办？

A: 应修改契约或调整实现，确保两者一致。建议使用契约测试自动验证。

### Q: 如何处理接口版本升级？

A: 使用 URL 版本控制（如 `/api/v1/users`、`/api/v2/users`）或请求头版本控制。
