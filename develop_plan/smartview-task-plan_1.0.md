# SmartView Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从零构建 SmartView 开发者模拟面试系统 MVP，支持 PDF 简历解析、Java 后端 / Agent 开发方向模拟面试、项目追问、场景设计题、报告复盘和参考答案。

**Architecture:** 采用 monorepo。React 前端只调用 Spring Boot；Spring Boot 负责业务主流程、鉴权、数据落库和任务编排；FastAPI + LangGraph 负责 AI 能力；RabbitMQ 承载异步任务；MySQL 是业务主库，Redis 保存短期状态，MinIO 保存 PDF，Chroma 保存知识、面经和简历切片向量。

**Tech Stack:** React、Ant Design、Spring Boot、MyBatis Plus、Swagger/OpenAPI、JWT、FastAPI、LangChain、LangGraph、RabbitMQ、MySQL、Redis、MinIO、Chroma、Docker Compose。

> **详细设计文档：** 参见 `develop_plan/plan_1.0.md`

> **核心规范文档（实施前必读）：**
> - `docs/interview-policy.md`：面试策略与执行规范，定义职责边界、阶段控制规则、候选池机制、幂等性和降级策略
> - `docs/resume-workflow.md`：简历处理工作流，定义向量入库时机、画像分析时序和版本一致性保障
> - `docs/architecture-improvements.md`：架构优化总结，记录已解决的 8 个核心问题及待实施清单

---

## 执行策略

这个计划按可交付版本拆分。每个版本结束时都必须有一个可运行、可测试、可演示的产品切片，而不是只完成某一层代码。

| 版本 | 目标 | 不做 |
| --- | --- | --- |
| v0.1 | 工程骨架、契约目录、基础设施、AI 编码规则 | 业务功能、真实 AI 面试 |
| v0.2 | 登录注册、前端基础布局、JWT 鉴权、统一响应 | 简历解析、面试 |
| v0.3 | PDF 简历上传、MinIO 存储、MQ 任务、FastAPI 简历解析闭环 | 知识库检索、面试问答 |
| v0.4 | 知识库与面经离线入库、Chroma 检索、方向画像分析 | 完整面试流程 |
| v0.5 | 模拟面试主流程、阶段控制、候选问题池、回答评估、下一题选择 | 报告深度优化、跨天续面 |
| v0.6 | 面试报告、参考答案、历史记录、软删除与清理 | 管理端、复杂数据分析 |
| v1.0 | 契约测试、集成测试、文档、部署脚本、质量收口 | 新增大功能 |

## 任务执行原则

1. **契约优先**：跨服务接口先写 `contracts/`，再写实现。
2. **主线优先**：优先跑通“上传简历 -> 解析画像 -> 开始面试 -> 回答问题 -> 生成报告”。
3. **前端不直连 AI**：React 只调用 Spring Boot，禁止直接调用 FastAPI。
4. **业务主库唯一**：Spring Boot 写 MySQL 主业务表，FastAPI 不直接写业务主表。
5. **AI 能力可替换**：FastAPI 内部可换模型，但对 Spring Boot 的 API 契约稳定。
6. **生成代码不手改**：OpenAPI 生成的 Client / DTO 不允许手工修改。
7. **测试跟随开发**：每个版本都有明确测试 gate。
8. **MVP 边界硬化**：第一版不做难度选择、公司选择、代码题、Web 管理端知识库录入、完整语音面试、流式输出。

---

## 目标代码结构

```text
SmartView/
  AGENTS.md
  README.md
  develop_plan/
    plan_1.0.md
    smartview-task-plan_1.0.md
  contracts/
    web-api/
      openapi.yaml
    ai-api/
      openapi.yaml
    mq/
      resume_parse_task.schema.json
      resume_parse_result.schema.json
      resume_vectorize_task.schema.json
      resume_vectorize_result.schema.json
      profile_analyze_task.schema.json
      profile_analyze_result.schema.json
      report_generate_task.schema.json
      report_generate_result.schema.json
  smartview-web/
  smartview-server/
  smartview-ai/
  smartview-infra/
  knowledge/
    interview_knowledge_base/
    interview_experience_cases/
  docs/
```

---

## v0.1：工程骨架与契约基础

> **目标：** 建立 monorepo、基础设施、接口契约目录、AI 编码规则和最小可启动服务。

### Phase 0：仓库规范与 AI 编码规则

#### Task 0.1 — 创建根目录规范文件

**要求：**

- 创建 `AGENTS.md`，写明 AI 编码工具必须遵守的规则。
- 创建根目录 `README.md`，说明项目模块和启动入口。
- 创建 `docs/`、`contracts/`、`knowledge/`、`smartview-infra/` 基础目录。

**关键文件：**

- `AGENTS.md`
- `README.md`
- `contracts/`
- `docs/`
- `knowledge/`
- `smartview-infra/`

**AGENTS.md 必须包含：**

- React 只调用 Spring Boot。
- Spring Boot 调 FastAPI 只能走 `AiServiceClient`。
- HTTP 契约存放在 `contracts/web-api/openapi.yaml` 和 `contracts/ai-api/openapi.yaml`。
- MQ 契约存放在 `contracts/mq/*.schema.json`。
- 禁止手写跨端重复 DTO。
- 禁止修改生成目录。
- 接口变更必须先修改契约。
- 完成任务前必须说明运行了哪些测试。

**验收标准：**

- 根目录能清楚看出 monorepo 结构。
- AI 工具读取 `AGENTS.md` 后能知道禁止项和契约规则。

#### Task 0.2 — 初始化契约目录

**要求：**

- 创建 `web-api`、`ai-api`、`mq` 三类契约目录。
- 为第一版主链路创建初始 schema 文件。
- `web-api/openapi.yaml` 至少定义 health、注册、登录、当前用户、简历上传、简历画像查询、创建面试会话、提交回答、报告查询接口。
- `ai-api/openapi.yaml` 至少定义 health、简历解析、生成方向画像分析、生成阶段计划、生成首题、评估回答并决策下一步、生成报告接口。

**关键文件：**

- `contracts/web-api/openapi.yaml`
- `contracts/ai-api/openapi.yaml`
- `contracts/mq/resume_parse_task.schema.json`
- `contracts/mq/resume_parse_result.schema.json`
- `contracts/mq/resume_vectorize_task.schema.json`
- `contracts/mq/resume_vectorize_result.schema.json`
- `contracts/mq/profile_analyze_task.schema.json`
- `contracts/mq/profile_analyze_result.schema.json`
- `contracts/mq/report_generate_task.schema.json`
- `contracts/mq/report_generate_result.schema.json`

**MQ 消息公共字段：**

- `taskId`
- `traceId`
- `messageType`
- `schemaVersion`
- `retryCount`
- `createdAt`

**验收标准：**

- 每类 MQ 消息都有 JSON Schema。
- OpenAPI 文件可以被 OpenAPI lint 工具解析。
- 文档中禁止出现未登记的 `messageType`。

### Phase 1：基础设施

#### Task 1.1 — Docker Compose 基础设施

**要求：**

- 使用 Docker Compose 启动 MySQL、Redis、RabbitMQ、MinIO、Chroma。
- 每个服务使用固定端口，便于前后端开发联调。
- 创建 `.env.example`，列出必要环境变量。

**关键文件：**

- `smartview-infra/docker-compose.yml`
- `smartview-infra/.env.example`
- `smartview-infra/mysql/init/`
- `smartview-infra/minio/`

**建议端口：**

- MySQL：`3306`
- Redis：`6379`
- RabbitMQ：`5672`
- RabbitMQ 管理端：`15672`
- MinIO API：`9000`
- MinIO Console：`9001`
- Chroma：`8001`

**验收标准：**

- `docker compose up -d` 后所有容器健康。
- RabbitMQ 管理端可访问。
- MinIO Console 可登录。
- Spring Boot 和 FastAPI 后续可通过 `.env` 获取连接信息。

#### Task 1.2 — 初始化 Spring Boot 工程

**要求：**

- 创建 `smartview-server/`。
- 引入 Web、Validation、Security、MyBatis Plus、MySQL Driver、Redis、RabbitMQ、MinIO、Swagger/OpenAPI、Lambok、JWT 相关依赖。
- 建立统一响应结构和全局异常处理。

**关键目录：**

- `smartview-server/src/main/java/com/smartview/common/api/`
- `smartview-server/src/main/java/com/smartview/common/exception/`
- `smartview-server/src/main/java/com/smartview/config/`
- `smartview-server/src/main/resources/application.yml`
- `smartview-server/src/test/java/com/smartview/`

**基础能力：**

- `/api/health` 返回服务健康状态。
- Swagger UI 可访问。
- 统一响应格式包含 `code`、`message`、`data`、`traceId`。

**验收标准：**

- Spring Boot 能启动。
- `/api/health` 返回成功。
- Swagger UI 能展示接口。
- 基础测试可运行。

#### Task 1.3 — 初始化 FastAPI 工程

**要求：**

- 创建 `smartview-ai/`。
- 建立 FastAPI 应用入口。
- 建立配置、日志、错误处理、Pydantic schema 基础结构。
- 暴露 `/api/v1/health`。

**关键目录：**

- `smartview-ai/app/main.py`
- `smartview-ai/app/core/config.py`
- `smartview-ai/app/core/logging.py`
- `smartview-ai/app/core/errors.py`
- `smartview-ai/app/schemas/`
- `smartview-ai/tests/`

**验收标准：**

- FastAPI 能启动。
- `/api/v1/health` 返回成功。
- `/docs` 可查看 OpenAPI。
- Python 测试框架可运行。

#### Task 1.4 — 初始化 React 前端工程

**要求：**

- 创建 `smartview-web/`。
- 使用 React + Ant Design。
- 建立路由、请求封装、基础布局、登录页基础页面、首页基础页面。
- 创建 `src/api/generated/` 目录并在 README 中说明该目录只能由 OpenAPI 生成工具写入。

**关键目录：**

- `smartview-web/src/app/`
- `smartview-web/src/api/`
- `smartview-web/src/api/generated/`
- `smartview-web/src/pages/login/`
- `smartview-web/src/pages/resume/`
- `smartview-web/src/pages/interview/`
- `smartview-web/src/pages/report/`

**验收标准：**

- 前端能启动。
- 首页能访问。
- 请求封装自动带 `traceId` 或接收后端返回的 `traceId`。
- `npm run typecheck` 可执行。

---

## v0.2：账号体系与前端基础流程

> **目标：** 用户可以注册、登录、保持会话，并进入系统主页面。

### Phase 2：数据库基础与用户体系

#### Task 2.1 — 创建用户表与基础迁移

**要求：**

- 创建 `user` 表。
- 建立通用字段：`id`、`created_at`、`updated_at`、`deleted`。
- 使用迁移脚本管理表结构。

**数据库字段：**

| 表 | 字段 | 含义 |
| --- | --- | --- |
| `user` | `id` | 用户 ID，主键 |
| `user` | `username` | 登录用户名，要求唯一 |
| `user` | `password_hash` | 加密后的密码，不保存明文密码 |
| `user` | `nickname` | 用户昵称 |
| `user` | `email` | 邮箱，可选 |
| `user` | `phone` | 手机号，可选 |
| `user` | `status` | 用户状态，例如 `ACTIVE`、`DISABLED` |
| `user` | `last_login_at` | 最近登录时间 |
| `user` | `created_at` | 创建时间 |
| `user` | `updated_at` | 更新时间 |
| `user` | `deleted` | 软删除标记，`0` 表示未删除，`1` 表示已删除 |

**关键文件：**

- `smartview-server/src/main/resources/db/migration/`
- `smartview-server/src/main/java/com/smartview/user/entity/User.java`
- `smartview-server/src/main/java/com/smartview/user/mapper/UserMapper.java`

**验收标准：**

- 应用启动后可自动创建或迁移用户表。
- 用户名唯一约束生效。
- 软删除字段存在。

#### Task 2.2 — 注册与登录接口

**要求：**

- 实现注册接口。
- 实现登录接口。
- 密码必须加密保存。
- 登录成功返回 JWT。
- 返回当前用户信息接口。

**关键文件：**

- `smartview-server/src/main/java/com/smartview/user/controller/AuthController.java`
- `smartview-server/src/main/java/com/smartview/user/service/AuthService.java`
- `smartview-server/src/main/java/com/smartview/security/`
- `smartview-server/src/main/java/com/smartview/user/dto/`

**接口：**

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/users/me`

**验收标准：**

- 注册成功后数据库有用户记录。
- 密码字段不是明文。
- 登录成功返回 JWT。
- 未登录访问受保护接口返回 401。

#### Task 2.3 — 前端登录注册页面

**要求：**

- 实现登录页。
- 实现注册页。
- 登录后保存 Token。
- 请求拦截器自动携带 Token。
- Token 失效时跳转登录页。

**关键文件：**

- `smartview-web/src/pages/login/LoginPage.tsx`
- `smartview-web/src/pages/login/RegisterPage.tsx`
- `smartview-web/src/features/auth/`
- `smartview-web/src/api/request.ts`
- `smartview-web/src/app/router.tsx`

**验收标准：**

- 用户可以从页面完成注册。
- 用户可以登录并进入系统。
- 刷新页面后仍能读取登录状态。
- 未登录访问业务页会跳转登录页。

#### Task 2.4 — 同步 web-api 契约

**要求：**

- Spring Boot 输出 OpenAPI。
- 将当前接口同步到 `contracts/web-api/openapi.yaml`。
- 前端根据契约生成 API Client。
- 页面调用生成 Client，而不是手写 URL。

**关键文件：**

- `contracts/web-api/openapi.yaml`
- `smartview-web/src/api/generated/`

**验收标准：**

- 生成 Client 后前端类型检查通过。
- 前端没有直接拼接 `/api/auth/login` 这类业务 URL。
- 契约文件中包含注册、登录、当前用户接口。

---

## v0.3：PDF 简历上传与解析闭环

> **目标：** 用户可以上传中文 PDF 简历，系统保存原文件，异步解析，生成结构化画像，并让用户确认。

### Phase 3：简历文件管理

#### Task 3.1 — 简历相关表结构

**要求：**

- 创建 `resume_file` 表。
- 创建 `resume_profile` 表。
- 创建 `ai_task` 表。
- 字段与 `plan_1.0.md` 的 7.1 保持一致。

**数据库字段：**

| 表 | 字段 | 含义 |
| --- | --- | --- |
| `resume_file` | `id` | 简历文件 ID，主键 |
| `resume_file` | `user_id` | 所属用户 ID |
| `resume_file` | `original_filename` | 用户上传时的原始文件名 |
| `resume_file` | `object_key` | MinIO 中的文件对象 Key |
| `resume_file` | `file_hash` | 文件 hash，用于去重或审计 |
| `resume_file` | `file_size` | 文件大小，单位字节 |
| `resume_file` | `mime_type` | 文件 MIME 类型，第一版主要为 `application/pdf` |
| `resume_file` | `parse_status` | 解析状态，例如 `PENDING`、`PROCESSING`、`SUCCESS`、`FAILED` |
| `resume_file` | `parse_task_id` | 对应的 AI 解析任务 ID |
| `resume_file` | `error_message` | 解析失败原因 |
| `resume_file` | `uploaded_at` | 上传时间 |
| `resume_file` | `created_at` | 创建时间 |
| `resume_file` | `updated_at` | 更新时间 |
| `resume_file` | `deleted` | 软删除标记 |
| `resume_profile` | `id` | 简历画像 ID，主键 |
| `resume_profile` | `user_id` | 所属用户 ID |
| `resume_profile` | `resume_file_id` | 对应的简历文件 ID |
| `resume_profile` | `candidate_name` | 候选人姓名 |
| `resume_profile` | `contact_info_json` | 联系方式 JSON，例如手机号、邮箱 |
| `resume_profile` | `education_json` | 教育经历 JSON |
| `resume_profile` | `work_experience_json` | 工作经历 JSON |
| `resume_profile` | `project_experience_json` | 项目经历 JSON |
| `resume_profile` | `skills_json` | 技能栈 JSON |
| `resume_profile` | `raw_text` | PDF 提取或 OCR 得到的简历原文 |
| `resume_profile` | `profile_json` | 完整结构化解析结果 JSON |
| `resume_profile` | `confirm_status` | 用户确认状态，例如 `UNCONFIRMED`、`CONFIRMED` |
| `resume_profile` | `confirmed_at` | 用户确认时间 |
| `resume_profile` | `version` | 画像版本号，用户重新上传或重新解析时递增 |
| `resume_profile` | `created_at` | 创建时间 |
| `resume_profile` | `updated_at` | 更新时间 |
| `resume_profile` | `deleted` | 软删除标记 |
| `ai_task` | `id` | 主键 ID |
| `ai_task` | `task_id` | 业务任务 ID，建议使用全局唯一字符串 |
| `ai_task` | `user_id` | 所属用户 ID |
| `ai_task` | `task_type` | 任务类型，例如 `RESUME_PARSE`、`PROFILE_ANALYZE`、`REPORT_GENERATE`、`CLEANUP` |
| `ai_task` | `task_status` | 任务状态，例如 `PENDING`、`PROCESSING`、`SUCCESS`、`FAILED`、`RETRYING` |
| `ai_task` | `biz_type` | 关联业务类型，例如 `RESUME_FILE`、`INTERVIEW_SESSION` |
| `ai_task` | `biz_id` | 关联业务 ID |
| `ai_task` | `request_payload_json` | 投递给 AI 服务的请求 JSON |
| `ai_task` | `result_payload_json` | AI 服务返回的结果 JSON |
| `ai_task` | `error_message` | 失败原因 |
| `ai_task` | `retry_count` | 当前重试次数 |
| `ai_task` | `max_retry` | 最大重试次数 |
| `ai_task` | `trace_id` | 链路追踪 ID |
| `ai_task` | `message_type` | MQ 消息类型 |
| `ai_task` | `schema_version` | MQ 消息 schema 版本 |
| `ai_task` | `started_at` | 任务开始时间 |
| `ai_task` | `finished_at` | 任务结束时间 |
| `ai_task` | `created_at` | 创建时间 |
| `ai_task` | `updated_at` | 更新时间 |
| `ai_task` | `deleted` | 软删除标记 |

**关键文件：**

- `smartview-server/src/main/resources/db/migration/`
- `smartview-server/src/main/java/com/smartview/resume/entity/ResumeFile.java`
- `smartview-server/src/main/java/com/smartview/resume/entity/ResumeProfile.java`
- `smartview-server/src/main/java/com/smartview/task/entity/AiTask.java`

**验收标准：**

- 三张表可成功迁移。
- `resume_file.user_id`、`resume_profile.resume_file_id` 有清晰关联。
- `ai_task.task_id` 唯一。

#### Task 3.2 — PDF 上传接口

**要求：**

- 实现 PDF 上传接口。
- 校验文件类型和大小。
- 保存 PDF 到 MinIO。
- 写入 `resume_file`。
- 创建 `RESUME_PARSE` 类型的 `ai_task`。
- 投递 RabbitMQ 简历解析任务。

**关键文件：**

- `smartview-server/src/main/java/com/smartview/resume/controller/ResumeController.java`
- `smartview-server/src/main/java/com/smartview/resume/service/ResumeFileService.java`
- `smartview-server/src/main/java/com/smartview/infra/minio/MinioService.java`
- `smartview-server/src/main/java/com/smartview/task/mq/ResumeTaskProducer.java`

**接口：**

- `POST /api/resumes`
- `GET /api/resumes/{resumeFileId}`

**验收标准：**

- 上传非 PDF 被拒绝。
- 上传成功后 MinIO 有文件。
- `resume_file.parse_status` 初始为 `PENDING` 或 `PROCESSING`。
- RabbitMQ 中产生解析任务消息。

#### Task 3.3 — FastAPI 简历解析能力

**要求：**

- 实现 PDF 文本提取。
- 文本不可用时使用 PaddleOCR 做中文 OCR 兜底。
- 使用 LLM 将简历文本转成结构化 JSON。
- 输出字段覆盖基本信息、教育、工作、项目、技能、原始文本。

**关键文件：**

- `smartview-ai/app/services/resume_parser.py`
- `smartview-ai/app/schemas/resume.py`
- `smartview-ai/app/api/v1/resume.py`
- `smartview-ai/app/workers/resume_worker.py`

**接口：**

- `POST /api/v1/resume/parse`

**验收标准：**

- 给定一份文本型中文 PDF，能返回结构化简历。
- 解析失败时返回可读错误。
- FastAPI 的 OpenAPI 同步到 `contracts/ai-api/openapi.yaml`。

#### Task 3.4 — Spring Boot 接收解析结果

**要求：**

- 实现 RabbitMQ 结果消费者。
- 校验 `resume_parse_result.schema.json`。
- 解析成功后写入 `resume_profile`。
- 更新 `resume_file.parse_status` 和 `ai_task.task_status`。
- 解析失败时记录 `error_message`。

**关键文件：**

- `smartview-server/src/main/java/com/smartview/task/mq/ResumeResultConsumer.java`
- `smartview-server/src/main/java/com/smartview/resume/service/ResumeProfileService.java`
- `contracts/mq/resume_parse_result.schema.json`

**验收标准：**

- 成功消息能落库。
- 失败消息能更新失败状态。
- 重复消息不会重复创建多份画像。

#### Task 3.5 — 简历确认页面

**要求：**

- 前端展示解析出的结构化简历。
- 用户可以确认解析结果。
- 支持轻量编辑关键字段。
- 确认后调用后端接口更新 `confirm_status`。

**关键文件：**

- `smartview-web/src/pages/resume/ResumeUploadPage.tsx`
- `smartview-web/src/pages/resume/ResumeConfirmPage.tsx`
- `smartview-web/src/features/resume/`

**接口：**

- `GET /api/resume-profiles/{profileId}`
- `PUT /api/resume-profiles/{profileId}`
- `POST /api/resume-profiles/{profileId}/confirm`

**验收标准：**

- 用户能上传 PDF 并看到解析中状态。
- 解析完成后能进入确认页。
- 确认后状态变为 `CONFIRMED`。

#### Task 3.6 — 简历切片向量入库

**要求：**

- 用户确认简历后，Spring Boot 立即创建简历向量入库任务并投递 MQ。
- 前端轮询向量入库状态（最多等待 60 秒），成功后才显示"选择面试方向"按钮。
- FastAPI 将 `resume_profile.raw_text`、项目经历、技能描述切片并写入 Chroma 的 `resume_profile_chunks` collection。
- 向量 metadata 必须包含 `user_id`、`resume_profile_id`、`profile_version`。
- 检索时过滤条件只能由服务端生成，禁止前端直接传入用户隔离字段。
- 简历删除、重新解析或版本更新时，需要清理旧版本向量。
- 向量入库失败时，前端允许用户重试，面试时降级使用 MySQL 中的完整简历。
- **详见**：`docs/resume-workflow.md` 第 2.1 节

**关键文件：**

- `contracts/mq/resume_vectorize_task.schema.json`
- `contracts/mq/resume_vectorize_result.schema.json`
- `smartview-ai/app/services/resume_vectorizer.py`
- `smartview-ai/app/workers/resume_vectorize_worker.py`
- `smartview-ai/app/retrievers/resume_retriever.py`

**验收标准：**

- 确认简历后能在 `resume_profile_chunks` 检索到该简历的项目和技能片段。
- 前端轮询向量入库状态，成功后才显示方向选择按钮。
- 不同用户、不同画像版本之间不会串租。
- Redis 或 Chroma 临时异常不会改变 MySQL 中已确认画像的权威状态。
- 向量入库失败时允许用户重试。

---

## v0.4：知识库、面经库与画像分析

> **目标：** 开发者可以离线录入八股和面经材料，系统能向量化检索，并在用户选择方向后生成该方向的面试画像分析。

### Phase 4：知识库离线入库

#### Task 4.1 — 知识材料目录规范

**要求：**

- 建立八股知识目录。
- 建立面经案例目录。
- 定义 Markdown 文件元信息格式。

**关键目录：**

- `knowledge/interview_knowledge_base/`
- `knowledge/interview_experience_cases/`
- `smartview-ai/scripts/`

**材料元信息建议：**

```text
title: 标题
category: Java 并发 / JVM / Spring / Agent / RAG / LangGraph
source_type: KNOWLEDGE_BASE 或 EXPERIENCE_CASE
role_direction: JAVA_BACKEND 或 AGENT_DEVELOPMENT
tags: 多个标签
```

**验收标准：**

- 至少准备少量 Java 后端八股样例。
- 至少准备少量 Agent 开发面经样例。
- 入库脚本能识别文件元信息。

#### Task 4.2 — Chroma 入库脚本

**要求：**

- 实现离线入库脚本。
- 将八股材料写入 `interview_knowledge_base` collection。
- 将面经材料写入 `interview_experience_cases` collection。
- 支持重复运行时更新或跳过重复内容。

**关键文件：**

- `smartview-ai/scripts/ingest_knowledge.py`
- `smartview-ai/app/clients/chroma_client.py`
- `smartview-ai/app/retrievers/knowledge_retriever.py`
- `smartview-ai/app/retrievers/experience_retriever.py`

**验收标准：**

- 脚本能把本地 Markdown 入库。
- 可按标签、方向、关键词检索。
- 八股和面经进入不同 collection。

#### Task 4.3 — 画像分析任务

**要求：**

- 向量入库成功后，用户选择面试方向（`JAVA_BACKEND` 或 `AGENT_DEVELOPMENT`）。
- Spring Boot 检查该方向的画像分析是否存在，不存在则创建 `PROFILE_ANALYZE` 任务。
- 画像分析任务创建前，先校验简历向量是否已成功入库。
- 前端轮询画像分析状态（最多等待 60 秒），成功后才显示"开始面试"按钮。
- FastAPI 根据已确认简历、简历向量片段、知识/面经检索结果生成 `profile_analysis`。
- 分析结果包括技能标签、项目图谱、能力线索、风险点、建议面试主题和阶段覆盖目标。
- 画像分析是系统内部的面试准备材料，用来生成阶段计划和出题策略。
- 唯一约束：`resume_profile_id + role_direction + profile_version`，避免重复生成。
- 画像分析失败时，允许用户重试，不允许开始面试。
- **详见**：`docs/resume-workflow.md` 第 2.2 节

**数据库字段：**

| 表 | 字段 | 含义 |
| --- | --- | --- |
| `profile_analysis` | `id` | 分析结果 ID，主键 |
| `profile_analysis` | `user_id` | 所属用户 ID |
| `profile_analysis` | `resume_profile_id` | 对应的简历画像 ID |
| `profile_analysis` | `role_direction` | 面试方向，例如 `JAVA_BACKEND`、`AGENT_DEVELOPMENT` |
| `profile_analysis` | `skill_tags_json` | 技能标签 JSON |
| `profile_analysis` | `project_graph_json` | 项目关系图谱 JSON，包括项目、技术栈、职责、亮点 |
| `profile_analysis` | `capability_hints_json` | 能力线索 JSON，例如工程能力、Agent 能力、系统设计能力 |
| `profile_analysis` | `risk_points_json` | 风险点 JSON，例如项目描述空泛、技术深度不足 |
| `profile_analysis` | `suggested_topics_json` | 建议面试主题 JSON |
| `profile_analysis` | `stage_targets_json` | 阶段覆盖目标 JSON，例如八股、项目追问、场景题的重点 |
| `profile_analysis` | `profile_version` | 对应的简历画像版本号 |
| `profile_analysis` | `model_name` | 生成该分析结果使用的模型名称 |
| `profile_analysis` | `model_version` | 模型版本或配置版本 |
| `profile_analysis` | `created_at` | 创建时间 |
| `profile_analysis` | `updated_at` | 更新时间 |
| `profile_analysis` | `deleted` | 软删除标记 |

**关键文件：**

- `smartview-server/src/main/java/com/smartview/resume/service/ProfileAnalysisTaskService.java`
- `smartview-server/src/main/resources/db/migration/`
- `smartview-server/src/main/java/com/smartview/profile/entity/ProfileAnalysis.java`
- `smartview-ai/app/services/profile_analyzer.py`
- `smartview-ai/app/workers/profile_worker.py`

**验收标准：**

- 选择方向后自动触发该方向画像分析。
- 画像分析任务创建前校验向量是否已入库。
- 前端轮询画像分析状态，成功后才显示"开始面试"按钮。
- `profile_analysis` 成功落库。
- 同一简历版本、同一方向只有一份有效画像分析。
- 画像分析失败时允许用户重试，不允许开始面试。

---

## v0.5：模拟面试主流程

> **目标：** 用户选择 Java 后端或 Agent 开发方向后，可以进行文字模拟面试。系统根据简历、知识库和面经动态提问。

### Phase 5：会话与问题模型

#### Task 5.1 — 面试相关表结构

**要求：**

- 创建 `interview_session` 表。
- 创建 `interview_question` 表。
- 创建 `interview_answer` 表。
- 创建 `answer_evaluation` 表。
- 字段与 `plan_1.0.md` 的 7.1、7.2 保持一致。

**数据库字段：**

| 表 | 字段 | 含义 |
| --- | --- | --- |
| `interview_session` | `id` | 面试会话 ID，主键 |
| `interview_session` | `user_id` | 所属用户 ID |
| `interview_session` | `resume_profile_id` | 使用的简历画像 ID |
| `interview_session` | `profile_analysis_id` | 使用的简历分析 ID |
| `interview_session` | `role_direction` | 用户选择的面试方向 |
| `interview_session` | `status` | 会话状态，例如 `CREATED`、`IN_PROGRESS`、`REPORTING`、`COMPLETED`、`CANCELLED`、`FAILED` |
| `interview_session` | `current_stage` | 当前内部阶段，例如 `BASIC`、`PROJECT`、`SCENARIO`、`REPORT` |
| `interview_session` | `current_topic` | 当前主题，例如某个项目、某个技术点、某类场景 |
| `interview_session` | `current_question_id` | 当前正在回答的问题 ID |
| `interview_session` | `question_count` | 已提出的问题数量 |
| `interview_session` | `expected_min_questions` | 预期最少问题数，用于进度展示 |
| `interview_session` | `expected_max_questions` | 预期最多问题数，用于进度展示 |
| `interview_session` | `stage_plan_json` | 阶段计划 JSON，记录阶段顺序、题量边界、必覆盖主题 |
| `interview_session` | `stage_coverage_json` | 阶段覆盖情况 JSON，记录已问主题、追问深度、切换原因 |
| `interview_session` | `graph_thread_id` | LangGraph thread ID，用于后续恢复流程 |
| `interview_session` | `latest_checkpoint_id` | 最近一次 LangGraph checkpoint ID |
| `interview_session` | `version` | 乐观锁版本号，用于防止并发提交回答 |
| `interview_session` | `end_reason` | 结束原因，例如 `AUTO_FINISH`、`USER_FINISH`、`CANCELLED`、`FAILED` |
| `interview_session` | `started_at` | 面试开始时间 |
| `interview_session` | `ended_at` | 面试结束时间 |
| `interview_session` | `created_at` | 创建时间 |
| `interview_session` | `updated_at` | 更新时间 |
| `interview_session` | `deleted` | 软删除标记 |
| `interview_question` | `id` | 问题 ID，主键 |
| `interview_question` | `session_id` | 所属面试会话 ID |
| `interview_question` | `user_id` | 所属用户 ID |
| `interview_question` | `question_order` | 当前会话中的问题序号 |
| `interview_question` | `parent_question_id` | 父问题 ID，用于表示追问关系 |
| `interview_question` | `stage` | 所属阶段，例如 `BASIC`、`PROJECT`、`SCENARIO` |
| `interview_question` | `question_type` | 问题类型，例如 `OPENING`、`FOLLOW_UP`、`SWITCH_TOPIC`、`STAGE_ENTRY` |
| `interview_question` | `topic` | 问题主题 |
| `interview_question` | `question_text` | 问题正文 |
| `interview_question` | `source_type` | 来源类型，例如 `KNOWLEDGE_BASE`、`EXPERIENCE_CASE`、`RESUME_PROJECT`、`MIXED` |
| `interview_question` | `knowledge_refs_json` | 引用的八股知识片段信息 |
| `interview_question` | `case_refs_json` | 引用的面经案例信息 |
| `interview_question` | `expected_points_json` | 期望回答要点 JSON |
| `interview_question` | `status` | 问题状态，例如 `ASKED`、`ANSWERED`、`SKIPPED` |
| `interview_question` | `asked_at` | 提问时间 |
| `interview_question` | `created_at` | 创建时间 |
| `interview_question` | `updated_at` | 更新时间 |
| `interview_question` | `deleted` | 软删除标记 |
| `interview_answer` | `id` | 回答 ID，主键 |
| `interview_answer` | `session_id` | 所属面试会话 ID |
| `interview_answer` | `question_id` | 对应问题 ID |
| `interview_answer` | `user_id` | 所属用户 ID |
| `interview_answer` | `answer_text` | 用户回答文本 |
| `interview_answer` | `answer_mode` | 回答方式，第一版主要为 `TEXT`，后续可扩展 `VOICE_TO_TEXT` |
| `interview_answer` | `duration_seconds` | 用户作答耗时，单位秒 |
| `interview_answer` | `request_id` | 回答提交幂等 ID，可来自 `Idempotency-Key` |
| `interview_answer` | `submitted_at` | 提交时间 |
| `interview_answer` | `created_at` | 创建时间 |
| `interview_answer` | `updated_at` | 更新时间 |
| `interview_answer` | `deleted` | 软删除标记 |
| `answer_evaluation` | `id` | 评估 ID，主键 |
| `answer_evaluation` | `session_id` | 所属面试会话 ID |
| `answer_evaluation` | `question_id` | 对应问题 ID |
| `answer_evaluation` | `answer_id` | 对应回答 ID |
| `answer_evaluation` | `score` | 回答得分，建议 0 到 100 |
| `answer_evaluation` | `level` | 回答等级，例如 `GOOD`、`NORMAL`、`WEAK` |
| `answer_evaluation` | `matched_points_json` | 已命中的要点 JSON |
| `answer_evaluation` | `missing_points_json` | 缺失要点 JSON |
| `answer_evaluation` | `risk_points_json` | 暴露的问题或风险 JSON |
| `answer_evaluation` | `next_action` | 下一步动作，例如 `FOLLOW_UP`、`SWITCH_TOPIC`、`NEXT_STAGE`、`FINISH` |
| `answer_evaluation` | `candidate_pool_snapshot_json` | 本次决策使用的候选问题池快照 JSON |
| `answer_evaluation` | `selected_next_question_id` | 被选中的下一题 ID，可为空 |
| `answer_evaluation` | `evaluation_text` | 简短评估说明 |
| `answer_evaluation` | `model_name` | 评估使用的模型名称 |
| `answer_evaluation` | `created_at` | 创建时间 |
| `answer_evaluation` | `updated_at` | 更新时间 |
| `answer_evaluation` | `deleted` | 软删除标记 |

**关键文件：**

- `smartview-server/src/main/resources/db/migration/`
- `smartview-server/src/main/java/com/smartview/interview/entity/InterviewSession.java`
- `smartview-server/src/main/java/com/smartview/interview/entity/InterviewQuestion.java`
- `smartview-server/src/main/java/com/smartview/interview/entity/InterviewAnswer.java`
- `smartview-server/src/main/java/com/smartview/interview/entity/AnswerEvaluation.java`

**验收标准：**

- 会话、问题、回答、评估四类数据可关联查询。
- `current_question_id` 能指向当前问题。
- `graph_thread_id` 和 `latest_checkpoint_id` 字段存在。
- `stage_plan_json`、`stage_coverage_json`、`version`、`request_id` 等字段存在。
- `interview_answer` 对同一 `question_id` 只能有一份有效回答。

#### Task 5.2 — 创建面试会话

**要求：**

- 用户选择面试方向。
- 后端校验用户已有已确认简历，并校验该方向的画像分析已成功生成。
- 根据方向画像分析生成阶段计划，阶段至少覆盖 `BASIC`、`PROJECT`、`SCENARIO`。
- 阶段计划需要包含每阶段最小/最大题量、必覆盖主题、单主题最大追问深度、总题量上限和切阶段条件。
- 创建 `interview_session`。
- 调用 FastAPI 生成首题。
- 写入 `interview_question`。
- 更新 `current_question_id`。

**关键文件：**

- `smartview-server/src/main/java/com/smartview/interview/controller/InterviewSessionController.java`
- `smartview-server/src/main/java/com/smartview/interview/service/InterviewSessionService.java`
- `smartview-server/src/main/java/com/smartview/ai/client/AiInterviewClient.java`
- `smartview-ai/app/api/v1/interview.py`
- `smartview-ai/app/graphs/interview_graph.py`

**接口：**

- `POST /api/interview-sessions`
- `GET /api/interview-sessions/{sessionId}`

**验收标准：**

- 没有确认简历时不能开始面试。
- 没有该方向画像分析时不能开始面试，应先触发或提示等待画像分析。
- 创建成功后返回首题和进度范围。
- 首题来源能记录为知识库、面经、简历项目或混合来源。
- 会话初始阶段计划不会一直锚定第一个问题，至少能覆盖八股、项目和场景三个阶段。

#### Task 5.3 — 候选问题池生成

**要求：**

- 每次向用户提出问题后，后台异步生成预生成候选池（同阶段换题 + 下一阶段入口）。
- 用户提交回答时，FastAPI 同步生成追问候选池（0-2 道基于回答事实的追问）。
- 候选问题池暂存在 Redis，key 为 `interview:candidate_pool:{sessionId}:{questionId}:{currentStage}`，TTL 30 分钟。
- 候选池只提供备选问题，不能直接决定下一步；最终动作由 Spring Boot 的 `StagePolicyEngine` 决定。
- Redis 丢失时从 `answer_evaluation.candidate_pool_snapshot_json` 重建。
- **详见**：`docs/interview-policy.md` 第 3 节

**关键文件：**

- `smartview-server/src/main/java/com/smartview/interview/service/FollowUpPoolService.java`
- `smartview-server/src/main/java/com/smartview/infra/redis/`
- `smartview-ai/app/services/question_generator.py`
- `smartview-ai/app/nodes/generate_question.py`
- `smartview-ai/app/nodes/build_candidate_pool.py`
- `smartview-ai/app/nodes/stage_controller.py`

**验收标准：**

- 每次问题返回后，Redis 中能看到对应的候选问题池。
- 候选问题数量有上限。
- 候选问题能标明来源和目标考察点。
- 单一主题连续追问不会超过阶段计划中的最大深度。

#### Task 5.4 — 提交回答与下一题选择

**要求：**

- 用户提交回答时必须携带 `request_id`（前端生成 UUID）。
- 后端校验 `current_question_id` 与提交的 `question_id` 一致。
- 后端通过 `interview_session.version` 做乐观锁，避免多端或重复点击造成并发推进。
- 后端保存 `interview_answer`，`request_id` 设置唯一索引保证同一 `question_id` 只能有一份有效回答。
- 调用 FastAPI 评估回答，FastAPI 只返回评估事实（得分、命中点、缺失点、风险点）和追问候选池（0-2 道）。
- Spring Boot 的 `StagePolicyEngine` 根据阶段计划、覆盖度、评估事实和候选池，独立决策 `nextAction`。
- 后端在同一事务内保存 `answer_evaluation`（包含决策快照）、下一道 `interview_question`、阶段覆盖度和会话当前指针。
- Redis 候选池缺失时从 `candidate_pool_snapshot_json` 重建，不应导致回答丢失。
- **详见**：`docs/interview-policy.md` 第 2.4 节（决策规则）、第 4 节（幂等性）

**关键文件：**

- `smartview-server/src/main/java/com/smartview/interview/controller/InterviewAnswerController.java`
- `smartview-server/src/main/java/com/smartview/interview/service/InterviewAnswerService.java`
- `smartview-server/src/main/java/com/smartview/interview/engine/StagePolicyEngine.java`（新增）
- `smartview-ai/app/services/answer_evaluator.py`
- `smartview-ai/app/nodes/evaluate_answer.py`
- `smartview-ai/app/nodes/build_candidate_pool.py`

**接口：**

- `POST /api/interview-sessions/{sessionId}/answers`

**验收标准：**

- 回答提交后能快速返回下一题。
- 重复提交同一个 `request_id` 返回同一结果，不重复推进会话。
- 提交过期题目或非当前题目会被拒绝。
- 用户回答“不会”或“不熟悉”时，系统能降级追问或切换主题。
- 达到主题覆盖或题量上限后，会话进入 `REPORTING` 并触发报告生成。

#### Task 5.5 — 前端面试页面

**要求：**

- 展示当前问题。
- 支持文本回答。
- 展示已完成问题数量和预期范围。
- 支持用户提前结束。
- 页面刷新后恢复当前会话。

**关键文件：**

- `smartview-web/src/pages/interview/InterviewPage.tsx`
- `smartview-web/src/features/interview/`
- `smartview-web/src/components/`

**验收标准：**

- 用户能从页面完整回答多轮问题。
- 刷新页面后仍显示当前题目和历史问答。
- 页面不展示内部 `current_stage` 或原始面试计划。

---

## v0.6：报告、参考答案、历史与清理

> **目标：** 面试结束后生成可学习的报告，包含准备度、岗位匹配度、风险点、建议、覆盖情况和每题参考答案。

### Phase 6：报告生成

#### Task 6.1 — 报告相关表结构

**要求：**

- 创建 `interview_report` 表。
- 创建 `reference_answer` 表。
- 字段与 `plan_1.0.md` 的 7.1 保持一致。

**数据库字段：**

| 表 | 字段 | 含义 |
| --- | --- | --- |
| `interview_report` | `id` | 报告 ID，主键 |
| `interview_report` | `session_id` | 对应面试会话 ID |
| `interview_report` | `user_id` | 所属用户 ID |
| `interview_report` | `resume_profile_id` | 使用的简历画像 ID |
| `interview_report` | `overall_score` | 综合得分 |
| `interview_report` | `readiness_level` | 面试准备度等级 |
| `interview_report` | `role_fit_score` | 岗位匹配度得分 |
| `interview_report` | `summary` | 总体评价 |
| `interview_report` | `strengths_json` | 优势点 JSON |
| `interview_report` | `weaknesses_json` | 薄弱点 JSON |
| `interview_report` | `risk_points_json` | 风险点 JSON |
| `interview_report` | `suggestions_json` | 学习建议 JSON |
| `interview_report` | `coverage_json` | 覆盖情况 JSON，例如基础、项目、场景覆盖比例 |
| `interview_report` | `status` | 报告状态，例如 `GENERATING`、`SUCCESS`、`FAILED` |
| `interview_report` | `generated_at` | 报告生成时间 |
| `interview_report` | `created_at` | 创建时间 |
| `interview_report` | `updated_at` | 更新时间 |
| `interview_report` | `deleted` | 软删除标记 |
| `reference_answer` | `id` | 参考答案 ID，主键 |
| `reference_answer` | `report_id` | 所属报告 ID |
| `reference_answer` | `session_id` | 所属面试会话 ID |
| `reference_answer` | `question_id` | 对应问题 ID |
| `reference_answer` | `answer_type` | 答案类型，例如 `BASIC_KEY_POINTS`、`PROJECT_STRUCTURE`、`SCENARIO_FRAMEWORK` |
| `reference_answer` | `reference_content` | 参考答案正文 |
| `reference_answer` | `key_points_json` | 关键要点 JSON |
| `reference_answer` | `tradeoffs_json` | 场景题中的权衡点 JSON |
| `reference_answer` | `created_at` | 创建时间 |
| `reference_answer` | `updated_at` | 更新时间 |
| `reference_answer` | `deleted` | 软删除标记 |

**关键文件：**

- `smartview-server/src/main/resources/db/migration/`
- `smartview-server/src/main/java/com/smartview/report/entity/InterviewReport.java`
- `smartview-server/src/main/java/com/smartview/report/entity/ReferenceAnswer.java`

**验收标准：**

- 报告和参考答案能关联到会话、问题和用户。

#### Task 6.2 — 报告生成任务

**要求：**

- 会话结束后创建 `REPORT_GENERATE` 异步任务。
- FastAPI 汇总问题、回答、评估和画像信息。
- 生成综合报告。
- 为每道题生成参考答案。

**关键文件：**

- `smartview-server/src/main/java/com/smartview/report/service/ReportTaskService.java`
- `smartview-server/src/main/java/com/smartview/task/mq/ReportTaskProducer.java`
- `smartview-server/src/main/java/com/smartview/task/mq/ReportResultConsumer.java`
- `smartview-ai/app/services/report_generator.py`
- `smartview-ai/app/workers/report_worker.py`

**报告内容：**

- 面试准备度
- 岗位匹配度
- 分项评分
- 优势点
- 薄弱点
- 风险点
- 学习建议
- 覆盖情况
- 每题参考答案

**参考答案规则：**

- 基础题：给关键知识点。
- 项目题：给优秀回答结构。
- 场景题：给分析框架、取舍和落地方案。

**验收标准：**

- 会话结束后报告状态进入 `GENERATING`。
- 任务成功后报告状态为 `SUCCESS`。
- 每道已回答问题都有参考答案。

#### Task 6.3 — 报告页面

**要求：**

- 展示整体评分和准备度。
- 展示岗位匹配度。
- 展示优势、问题、风险和建议。
- 展示题目、用户回答、评估和参考答案。
- 展示覆盖情况，不展示原始面试计划。

**关键文件：**

- `smartview-web/src/pages/report/ReportPage.tsx`
- `smartview-web/src/features/report/`

**接口：**

- `GET /api/interview-sessions/{sessionId}/report`
- `GET /api/reports/{reportId}`

**验收标准：**

- 用户可以从面试结束页进入报告页。
- 报告生成中有明确状态。
- 报告失败时能提示重试或稍后查看。

### Phase 7：历史记录与清理

#### Task 7.1 — 简历和面试历史

**要求：**

- 用户可以查看历史简历。
- 用户可以查看历史面试会话。
- 用户可以进入已完成报告。

**关键文件：**

- `smartview-server/src/main/java/com/smartview/resume/controller/ResumeHistoryController.java`
- `smartview-server/src/main/java/com/smartview/interview/controller/InterviewHistoryController.java`
- `smartview-web/src/pages/resume/ResumeHistoryPage.tsx`
- `smartview-web/src/pages/interview/InterviewHistoryPage.tsx`

**验收标准：**

- 历史列表只展示当前用户数据。
- 删除后的记录默认不展示。
- 可从历史会话进入报告。

#### Task 7.2 — 软删除与物理清理

**要求：**

- 简历和面试记录优先软删除。
- 后台清理任务删除 MinIO 文件和 Chroma 向量。
- 保留必要审计信息。

**关键文件：**

- `smartview-server/src/main/java/com/smartview/cleanup/`
- `smartview-ai/app/workers/cleanup_worker.py`
- `contracts/mq/cleanup_task.schema.json`

**验收标准：**

- 用户删除简历后业务列表不可见。
- 后台任务可清理对应 MinIO 文件。
- 清理失败可重试。

---

## v1.0：质量收口、契约测试与部署文档

> **目标：** 让 MVP 具备可演示、可联调、可回归、可部署的工程质量。

### Phase 8：契约测试

#### Task 8.1 — OpenAPI 生成与校验

**要求：**

- Spring Boot 生成 `web-api/openapi.yaml`。
- FastAPI 生成 `ai-api/openapi.yaml`。
- 前端根据 `web-api` 生成 Client。
- Spring Boot 根据 `ai-api` 生成或校验 AI DTO / Client。

**关键文件：**

- `contracts/web-api/openapi.yaml`
- `contracts/ai-api/openapi.yaml`
- `smartview-web/src/api/generated/`
- `smartview-server/src/main/java/com/smartview/ai/client/`

**验收标准：**

- OpenAPI lint 通过。
- 前端 typecheck 通过。
- Spring Boot 调 FastAPI 的 DTO 与契约一致。

#### Task 8.2 — MQ Schema 校验

**要求：**

- Spring Boot 投递 MQ 前校验 schema。
- Spring Boot 消费结果前校验 schema。
- FastAPI Worker 消费任务前校验 schema。
- FastAPI Worker 投递结果前校验 schema。

**关键文件：**

- `contracts/mq/*.schema.json`
- `smartview-server/src/main/java/com/smartview/task/mq/`
- `smartview-ai/app/schemas/mq.py`
- `smartview-ai/app/workers/`

**验收标准：**

- 缺少 `taskId`、`traceId`、`schemaVersion` 的消息会被拒绝。
- 未登记 `messageType` 的消息会被拒绝。
- schema 版本不兼容时能记录明确错误。

### Phase 9：集成测试

#### Task 9.1 — 主链路集成测试

**要求：**

- 使用 Docker Compose 启动依赖。
- 使用测试 PDF 或 mock AI 服务跑通主链路。
- 覆盖登录、上传、解析、确认、面试、回答、报告。

**主链路：**

```text
注册登录
  -> 上传 PDF 简历
  -> 创建解析任务
  -> FastAPI 解析
  -> 用户确认画像
  -> 简历切片向量入库
  -> 选择面试方向
  -> 生成该方向画像分析
  -> 创建面试会话
  -> 生成阶段计划和首题
  -> 回答多轮问题并覆盖 BASIC / PROJECT / SCENARIO
  -> 结束面试
  -> 生成报告
```

**验收标准：**

- 主链路测试可以一键运行。
- 任一服务接口字段变化导致测试失败。
- 面试流程能验证阶段切换，而不是只围绕首题连续追问。
- 测试完成后能清理测试数据。

#### Task 9.2 — 异常链路测试

**要求：**

- 覆盖上传非 PDF。
- 覆盖 PDF 解析失败。
- 覆盖 RabbitMQ 消息重复投递。
- 覆盖 FastAPI 超时。
- 覆盖重复提交回答。
- 覆盖提交非当前题目。
- 覆盖 Redis 候选问题池丢失后重建。
- 覆盖 MQ 投递成功但消费重复的幂等场景。
- 覆盖报告生成失败。

**验收标准：**

- 用户能看到明确错误状态。
- 数据库任务状态正确。
- 失败任务可重试或可标记失败。

### Phase 10：文档与部署

#### Task 10.1 — 本地启动文档

**要求：**

- 写清楚依赖安装。
- 写清楚基础设施启动。
- 写清楚三个服务启动。
- 写清楚知识库入库。
- 写清楚测试命令。

**关键文件：**

- `README.md`
- `docs/local-development.md`
- `docs/knowledge-ingestion.md`

**验收标准：**

- 新开发者按文档可以启动完整系统。
- 文档中包含常见问题和排查方式。

#### Task 10.2 — API 与契约说明

**要求：**

- 说明 `contracts/` 的作用。
- 说明接口变更流程。
- 说明生成代码目录不可手改。
- 说明 MQ 消息字段规范。

**关键文件：**

- `docs/api-contracts.md`
- `docs/mq-contracts.md`

**验收标准：**

- AI 编码工具和人类开发者都能按文档执行接口变更。
- 文档与 `AGENTS.md` 不冲突。

---

## 最小验收清单

MVP 完成时，必须满足：

- [ ] 用户可以注册、登录、退出。
- [ ] 用户可以上传中文 PDF 简历。
- [ ] 系统可以保存 PDF 到 MinIO。
- [ ] 系统可以解析文本型 PDF 并生成结构化画像。
- [ ] 用户可以确认简历画像。
- [ ] 系统可以在确认简历后完成简历切片向量入库。
- [ ] 用户可以选择 Java 后端或 Agent 开发方向。
- [ ] 系统可以在选择方向后生成该方向画像分析。
- [ ] 开发者可以离线导入八股知识和面经材料。
- [ ] 系统可以基于简历、知识库和面经提出问题。
- [ ] 系统可以在每次提问后生成候选问题池。
- [ ] 系统可以按阶段计划控制追问、换题、切阶段和结束。
- [ ] 用户提交回答后，系统可以评估并返回下一题。
- [ ] 用户重复提交回答不会重复推进会话。
- [ ] 用户可以提前结束面试。
- [ ] 系统可以生成面试报告和每题参考答案。
- [ ] 页面刷新后可以恢复当前面试会话。
- [ ] 前端不直接调用 FastAPI。
- [ ] Spring Boot 不绕过 `AiServiceClient` 调 FastAPI。
- [ ] MQ 消息符合 JSON Schema。
- [ ] MQ 任务投递、消费和重试具备幂等保障。
- [ ] OpenAPI 契约可以生成前端 Client。
- [ ] 主链路集成测试通过。

---

## 推荐提交节奏

每个 Task 完成后单独提交，提交信息建议：

```text
chore: initialize smartview monorepo structure
chore: add infrastructure docker compose
feat: add user authentication api
feat: add resume upload workflow
feat: add resume parsing worker
feat: add knowledge ingestion pipeline
feat: add interview session workflow
feat: add interview report generation
test: add contract and integration tests
docs: add local development guide
```

---

## 执行顺序建议

不要并行开发所有模块。推荐顺序：

1. v0.1 先完成工程骨架、契约和基础设施。
2. v0.2 完成账号体系和前端基础。
3. v0.3 打通 PDF 上传与解析。
4. v0.4 建立知识检索能力。
5. v0.5 实现面试主流程。
6. v0.6 做报告、历史和清理。
7. v1.0 做契约测试、集成测试和文档收口。

其中，v0.3 完成后就是第一个真正可演示切片；v0.5 完成后就是核心产品体验；v1.0 完成后才适合作为求职项目或作品集展示。
