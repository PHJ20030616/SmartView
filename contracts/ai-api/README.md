# AI API 契约

本目录包含 SmartView FastAPI AI 服务对 Spring Boot 暴露的 API 契约。

## 文件说明

- `openapi.yaml` - OpenAPI 3.0 规范文件

## 路径版本策略

- **从 v1 开始显式版本化**：如 `/api/v1/resume/parse`、`/api/v1/profile/analyze`
- **与 Web API 区分**：Web API 第一版不带版本号，AI API 从 v1 开始带版本
- **原因**：AI 服务是内部服务，显式版本化便于后续模型升级和能力扩展时的兼容性管理

## 使用规范

### 契约变更流程

1. 修改 `openapi.yaml` 契约文件
2. 生成 Spring Boot Client 或 DTO
3. 实现 FastAPI 业务逻辑
4. 运行契约测试验证

### 代码生成

**Spring Boot 生成命令**：
```bash
cd smartview-server
mvn generate-sources
```

**FastAPI 校验**：
FastAPI 启动时自动从代码生成 OpenAPI 文档并校验。

## 认证方式

- 使用固定 API Key 认证
- 请求头：`X-API-Key: <api_key>`
- 密钥配置在环境变量 `AI_SERVICE_API_KEY`
- 必须在 `components.securitySchemes` 中定义 API Key 认证方案

## 超时与重试

- 同步调用超时：60 秒
- 失败后通过 MQ 异步重试最多 3 次
