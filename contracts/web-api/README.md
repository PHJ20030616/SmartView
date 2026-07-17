# Web API 契约

本目录包含 SmartView Spring Boot 后端对外暴露的 Web API 契约。

## 文件说明

- `openapi.yaml` - OpenAPI 3.0 规范文件

## 路径版本策略

- **第一版不带版本号**：如 `/api/auth/login`、`/api/resumes`
- **后续版本显式版本化**：如 `/api/v2/auth/login`
- **原因**：第一版作为默认版本，简化 URL；需要破坏性变更时再引入版本号

## 使用规范

### 契约变更流程

1. 修改 `openapi.yaml` 契约文件
2. 生成前端 TypeScript Client
3. 实现后端业务逻辑
4. 运行契约测试验证

### 代码生成

**前端生成命令**：
```bash
cd smartview-web
npm run generate:api
```

**后端校验**：
Spring Boot 启动时自动校验实现是否符合契约。

## 注意事项

- 禁止手写与契约重复的 DTO
- 接口变更必须先修改契约
- 生成目录不可手动修改
- 必须在 `components.securitySchemes` 中定义 JWT Bearer Token 认证
