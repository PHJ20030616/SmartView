# Task 0.2 — 初始化契约目录执行计划

## 任务目标

创建契约目录结构，为 SmartView 项目建立完整的 API 契约和消息契约基础。

## 设计决策

### 1. 服务间认证
- **AI 服务认证方式**：固定 API Key
- **实现**：Spring Boot 在请求头 `X-API-Key` 中携带配置的密钥，FastAPI 使用中间件校验

### 2. 文件传递
- **简历文件传递方式**：MinIO 预签名 URL
- **实现**：Spring Boot 生成临时下载 URL（有效期 1 小时），FastAPI 直接从 MinIO 下载

### 3. 容错策略
- **超时与重试**：同步调用超时 60s，失败后通过 MQ 异步重试最多 3 次
- **实现**：AiServiceClient 设置 60s 超时，捕获异常后创建 ai_task 记录并投递 MQ

## 执行步骤

### 步骤 1：创建契约目录结构

创建以下目录和 README 文件：

```
contracts/
├── web-api/
│   ├── README.md
│   └── openapi.yaml
├── ai-api/
│   ├── README.md
│   └── openapi.yaml
└── mq/
    └── README.md
```

**验收**：目录结构创建成功，每个目录包含 README 说明文件。

---

### 步骤 2：编写 web-api/openapi.yaml

定义 Spring Boot 后端对外暴露的 Web API 契约，至少包含以下接口：

#### 2.1 健康检查
- `GET /api/health` - 服务健康状态

#### 2.2 认证相关
- `POST /api/auth/register` - 用户注册
- `POST /api/auth/login` - 用户登录
- `GET /api/users/me` - 获取当前用户信息

#### 2.3 简历相关
- `POST /api/resumes` - 上传简历文件
- `GET /api/resumes/{resumeFileId}` - 获取简历文件信息
- `GET /api/resume-profiles/{profileId}` - 获取简历画像
- `PUT /api/resume-profiles/{profileId}` - 更新简历画像
- `POST /api/resume-profiles/{profileId}/confirm` - 确认简历画像

#### 2.4 面试相关
- `POST /api/interview-sessions` - 创建面试会话
- `GET /api/interview-sessions/{sessionId}` - 获取面试会话详情
- `POST /api/interview-sessions/{sessionId}/answers` - 提交回答

#### 2.5 报告相关
- `GET /api/interview-sessions/{sessionId}/report` - 获取面试报告
- `GET /api/reports/{reportId}` - 获取报告详情

**关键设计**：
- 统一响应格式：`{ code, message, data, traceId, timestamp }`
- 认证方式：JWT Token，通过 `Authorization: Bearer <token>` 传递
- 错误码规范：400/401/403/404/409/422/500/503
- 路径版本策略：Web API 第一版不带版本号（如 `/api/auth/login`），后续版本可通过 `/api/v2/` 扩展

**验收**：
- OpenAPI 3.0 格式正确
- 所有接口包含完整的请求参数、响应结构和错误码定义
- `components.securitySchemes` 中必须定义 JWT Bearer Token 认证方案
- 可被 Swagger UI 或 OpenAPI Generator 解析

---

### 步骤 3：编写 ai-api/openapi.yaml

定义 FastAPI AI 服务对 Spring Boot 暴露的 API 契约，至少包含以下接口：

#### 3.1 健康检查
- `GET /api/v1/health` - AI 服务健康状态

#### 3.2 简历解析
- `POST /api/v1/resume/parse` - 解析简历
  - 请求：`{ fileUrl, mimeType, traceId }`
  - 响应：`{ candidateName, contactInfo, education, workExperience, projectExperience, skills, rawText }`

#### 3.3 画像分析
- `POST /api/v1/profile/analyze` - 生成方向画像分析
  - 请求：`{ resumeProfileId, roleDirection, traceId }`
  - 响应：`{ skillTags, projectGraph, capabilityHints, riskPoints, suggestedTopics, stageTargets }`

#### 3.4 面试流程
- `POST /api/v1/interview/stage-plan` - 生成阶段计划
  - 请求：`{ profileAnalysisId, roleDirection, traceId }`
  - 响应：`{ stages: [{ stage, minQuestions, maxQuestions, topics, maxFollowUpDepth }], totalMaxQuestions }`

- `POST /api/v1/interview/first-question` - 生成首题
  - 请求：`{ sessionId, stagePlan, resumeProfileId, profileVersion, traceId }`
  - 响应：`{ questionText, questionType, sourceType, expectedPoints, knowledgeRefs, caseRefs }`

- `POST /api/v1/interview/evaluate` - 评估回答并生成候选池
  - 请求：`{ sessionId, questionId, answerText, sessionContext, traceId }`
  - 响应：`{ score, level, matchedPoints, missingPoints, riskPoints, followUpCandidates: [{ questionText, reason }] }`

#### 3.5 报告生成
- `POST /api/v1/interview/report` - 生成面试报告
  - 请求：`{ sessionId, traceId }`
  - 响应：`{ overallScore, readinessLevel, roleFitScore, summary, strengths, weaknesses, riskPoints, suggestions, coverage, referenceAnswers }`

**关键设计**：
- 认证方式：固定 API Key，通过 `X-API-Key` 请求头传递
- 所有接口要求 `traceId` 用于链路追踪
- 超时控制：客户端设置 60s 超时
- 路径版本策略：AI API 从 v1 开始显式版本化（如 `/api/v1/resume/parse`），与 Web API 区分（Web API 第一版不带版本号）

**验收**：
- OpenAPI 3.0 格式正确
- 所有接口包含完整的 DTO 定义
- `components.securitySchemes` 中必须定义 API Key 认证方案（`apiKey` in `header`）

---

### 步骤 4：创建 MQ 消息契约

为主链路异步任务创建 JSON Schema 文件，每个任务包含 task 和 result 两个 schema。

**任务触发时序说明**：
- **简历解析任务**：用户上传 PDF 后由 Spring Boot 立即创建并投递
- **简历向量化任务**：用户确认简历后由 Spring Boot 创建并投递，FastAPI 消费后将简历切片写入 Chroma
- **画像分析任务**：用户选择面试方向后，Spring Boot 先校验简历向量已成功入库，再创建并投递
- **报告生成任务**：面试会话结束后由 Spring Boot 创建并投递

#### 4.1 简历解析任务
**文件**：`contracts/mq/resume_parse_task.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ResumeParseTask",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "fileUrl", "mimeType", "resumeFileId"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["RESUME_PARSE_TASK"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "fileUrl": { "type": "string", "format": "uri" },
    "mimeType": { "type": "string" },
    "resumeFileId": { "type": "string" }
  }
}
```

**文件**：`contracts/mq/resume_parse_result.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ResumeParseResult",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "resumeFileId", "success"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["RESUME_PARSE_RESULT"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "resumeFileId": { "type": "string" },
    "success": { "type": "boolean" },
    "candidateName": { "type": "string" },
    "contactInfo": { "type": "object" },
    "education": { "type": "array" },
    "workExperience": { "type": "array" },
    "projectExperience": { "type": "array" },
    "skills": { "type": "array" },
    "rawText": { "type": "string" },
    "errorMessage": { "type": "string" }
  }
}
```

#### 4.2 简历向量化任务
**文件**：`contracts/mq/resume_vectorize_task.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ResumeVectorizeTask",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "resumeProfileId", "profileVersion"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["RESUME_VECTORIZE_TASK"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "resumeProfileId": { "type": "string" },
    "profileVersion": { "type": "integer" }
  }
}
```

**文件**：`contracts/mq/resume_vectorize_result.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ResumeVectorizeResult",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "resumeProfileId", "success"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["RESUME_VECTORIZE_RESULT"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "resumeProfileId": { "type": "string" },
    "success": { "type": "boolean" },
    "chunksCount": { "type": "integer" },
    "errorMessage": { "type": "string" }
  }
}
```

#### 4.3 画像分析任务
**文件**：`contracts/mq/profile_analyze_task.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ProfileAnalyzeTask",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "resumeProfileId", "roleDirection", "profileVersion", "vectorizeCompleted"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["PROFILE_ANALYZE_TASK"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "resumeProfileId": { "type": "string" },
    "roleDirection": { "type": "string", "enum": ["JAVA_BACKEND", "AGENT_DEVELOPMENT"] },
    "profileVersion": { "type": "integer", "description": "简历画像版本号，确保使用正确版本的简历数据" },
    "vectorizeCompleted": { "type": "boolean", "description": "简历向量是否已成功入库，画像分析任务投递前必须校验为 true" }
  }
}
```

**文件**：`contracts/mq/profile_analyze_result.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ProfileAnalyzeResult",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "resumeProfileId", "success"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["PROFILE_ANALYZE_RESULT"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "resumeProfileId": { "type": "string" },
    "success": { "type": "boolean" },
    "skillTags": { "type": "array" },
    "projectGraph": { "type": "object" },
    "capabilityHints": { "type": "object" },
    "riskPoints": { "type": "array" },
    "suggestedTopics": { "type": "array" },
    "stageTargets": { "type": "object" },
    "errorMessage": { "type": "string" }
  }
}
```

#### 4.4 报告生成任务
**文件**：`contracts/mq/report_generate_task.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ReportGenerateTask",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "sessionId"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["REPORT_GENERATE_TASK"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "sessionId": { "type": "string" }
  }
}
```

**文件**：`contracts/mq/report_generate_result.schema.json`

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ReportGenerateResult",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "retryCount", "createdAt", "sessionId", "success"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "enum": ["REPORT_GENERATE_RESULT"] },
    "schemaVersion": { "type": "string", "enum": ["1.0.0"] },
    "retryCount": { "type": "integer", "minimum": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "sessionId": { "type": "string" },
    "success": { "type": "boolean" },
    "reportId": { "type": "string" },
    "errorMessage": { "type": "string" }
  }
}
```

**验收**：
- 每个 MQ 消息都有对应的 JSON Schema
- 所有 schema 包含必需的公共字段
- messageType 枚举值唯一且明确
- 为每个 MQ schema 创建一份测试数据示例文件（`test-data/mq/` 目录）

---

### 步骤 5：创建契约 README 文档

#### 5.1 web-api/README.md

```markdown
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
```

#### 5.2 ai-api/README.md

```markdown
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
```

#### 5.3 mq/README.md

```markdown
# MQ 消息契约

本目录包含 SmartView 系统中 RabbitMQ 消息的 JSON Schema 定义。

## 文件说明

| 文件 | 消息类型 | 说明 |
|------|---------|------|
| `resume_parse_task.schema.json` | RESUME_PARSE_TASK | 简历解析任务 |
| `resume_parse_result.schema.json` | RESUME_PARSE_RESULT | 简历解析结果 |
| `resume_vectorize_task.schema.json` | RESUME_VECTORIZE_TASK | 简历向量化任务 |
| `resume_vectorize_result.schema.json` | RESUME_VECTORIZE_RESULT | 简历向量化结果 |
| `profile_analyze_task.schema.json` | PROFILE_ANALYZE_TASK | 画像分析任务 |
| `profile_analyze_result.schema.json` | PROFILE_ANALYZE_RESULT | 画像分析结果 |
| `report_generate_task.schema.json` | REPORT_GENERATE_TASK | 报告生成任务 |
| `report_generate_result.schema.json` | REPORT_GENERATE_RESULT | 报告生成结果 |

## 公共字段规范

所有 MQ 消息必须包含以下公共字段：

| 字段 | 类型 | 必需 | 说明 |
|-----|------|------|------|
| `taskId` | string (uuid) | 是 | 任务唯一标识 |
| `traceId` | string (uuid) | 是 | 链路追踪 ID |
| `messageType` | string (enum) | 是 | 消息类型枚举 |
| `schemaVersion` | string | 是 | Schema 版本号，当前为 1.0.0 |
| `retryCount` | integer | 是 | 当前重试次数 |
| `createdAt` | string (date-time) | 是 | 消息创建时间 |

## 重试策略

- **最大重试次数**：所有任务最多重试 3 次
- **retryCount 规则**：生产者投递时 `retryCount` 初始为 0，消费失败后递增
- **死信队列**：`retryCount` 达到 3 次后，消息进入死信队列 `smartview.dlx`
- **重试间隔**：建议使用指数退避策略（1s、2s、4s）

## 消息结果规范

所有 `*_result` 消息必须遵循以下规则：
- 当 `success=true` 时，`errorMessage` 字段应为空或不存在
- 当 `success=false` 时，`errorMessage` 字段为必需，包含明确的错误原因

## 消息类型登记

| messageType | 队列名称 | 说明 |
|------------|---------|------|
| RESUME_PARSE_TASK | smartview.resume.parse.task | 简历解析任务 |
| RESUME_PARSE_RESULT | smartview.resume.parse.result | 简历解析结果 |
| RESUME_VECTORIZE_TASK | smartview.resume.vectorize.task | 简历向量化任务 |
| RESUME_VECTORIZE_RESULT | smartview.resume.vectorize.result | 简历向量化结果 |
| PROFILE_ANALYZE_TASK | smartview.profile.analyze.task | 画像分析任务 |
| PROFILE_ANALYZE_RESULT | smartview.profile.analyze.result | 画像分析结果 |
| REPORT_GENERATE_TASK | smartview.report.generate.task | 报告生成任务 |
| REPORT_GENERATE_RESULT | smartview.report.generate.result | 报告生成结果 |

## 使用规范

### 契约变更流程

1. 修改对应的 JSON Schema 文件
2. 更新 `messageType` 登记表
3. 重新生成消息校验代码
4. 更新生产者和消费者代码

### 代码生成

**Spring Boot**：
```bash
# 生成消息 DTO 和校验器
mvn generate-sources
```

**FastAPI**：
```bash
# 生成 Pydantic 模型
python scripts/generate_mq_schemas.py
```

## 注意事项

- 所有未登记的 `messageType` 会被拒绝
- Schema 版本不兼容时会记录错误并进入死信队列
- 消息投递和消费必须校验 JSON Schema
```

**验收**：
- 每个契约目录包含清晰的 README
- README 包含变更流程、代码生成命令和注意事项

---

## 最终验收标准

1. ✅ 目录结构创建完整：`contracts/web-api/`、`contracts/ai-api/`、`contracts/mq/`
2. ✅ `web-api/openapi.yaml` 包含至少 9 个主链路接口，并定义 JWT 认证 securitySchemes
3. ✅ `ai-api/openapi.yaml` 包含至少 7 个 AI 能力接口，并定义 API Key 认证 securitySchemes
4. ✅ MQ 契约包含 8 个 JSON Schema 文件（4 种任务 × 2）
5. ✅ 所有 OpenAPI 文件可被 OpenAPI lint 工具解析
6. ✅ 所有 JSON Schema 符合 draft-07 规范
7. ✅ 每个契约目录包含 README 说明，并明确路径版本策略
8. ✅ 所有 `messageType` 已在 `mq/README.md` 中登记
9. ✅ `profile_analyze_task.schema.json` 包含 `vectorizeCompleted` 字段用于前置校验
10. ✅ `mq/README.md` 明确定义重试策略和 `retryCount` 使用规则
11. ✅ `test-data/mq/` 目录包含每个 schema 的测试数据示例

## 测试方法

### OpenAPI 校验
```bash
# 使用 openapi-generator 校验
npx @openapitools/openapi-generator-cli validate -i contracts/web-api/openapi.yaml
npx @openapitools/openapi-generator-cli validate -i contracts/ai-api/openapi.yaml
```

### JSON Schema 校验
```bash
# 使用 ajv-cli 校验
npx ajv-cli validate --all-errors -s contracts/mq/resume_parse_task.schema.json -d test-data/resume_parse_task.json
```

## 风险与注意事项

1. **契约变更影响范围**：修改契约会同时影响前后端，需要协调发布
2. **向后兼容性**：Schema 版本升级时需要保持向后兼容或提供迁移方案
3. **messageType 冲突**：新增消息类型前必须检查是否已存在
4. **生成代码覆盖**：生成目录中的代码会被完全覆盖，禁止手动修改

## 后续任务依赖

本任务完成后，以下任务可以并行进行：
- Task 1.2 - 初始化 Spring Boot 工程（依赖 web-api 契约）
- Task 1.3 - 初始化 FastAPI 工程（依赖 ai-api 契约）
- Task 1.4 - 初始化 React 前端工程（依赖 web-api 契约）
