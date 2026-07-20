# AGENTS.md instructions

## 最高优先级规则：必要的中文注释

> **这是整个 SmartView 项目最重要、最高优先级的代码规则。**

- 新增或修改任何代码时，必须在必要位置添加准确、清晰且有信息量的中文注释。
- 必须重点注释复杂逻辑、非直观业务规则、关键流程、边界条件、异常处理、兼容性处理、安全约束以及重要实现取舍，确保后续维护者能够理解代码为什么这样实现。
- 注释必须与代码行为保持一致；修改代码逻辑时，必须同步检查并更新相关注释。
- 禁止为了满足规则而添加复述代码、显而易见或无实际信息量的机械注释。
- 任务验收和代码审查时，必须将“必要位置是否具备必要的中文注释”作为强制检查项；缺少必要中文注释的代码不得视为完成。

<!-- context7 -->
Use Context7 MCP to fetch current documentation whenever the user asks about a library, framework, SDK, API, CLI tool, or cloud service -- even well-known ones like React, Next.js, Prisma, Express, Tailwind, Django, or Spring Boot. This includes API syntax, configuration, version migration, library-specific debugging, setup instructions, and CLI tool usage. Use even when you think you know the answer -- your training data may not reflect recent changes. Prefer this over web search for library docs.

Do not use for: refactoring, writing scripts from scratch, debugging business logic, code review, or general programming concepts.

## Steps

1. Always start with `resolve-library-id` using the library name and the user's question, unless the user provides an exact library ID in `/org/project` format
2. Pick the best match (ID format: `/org/project`) by: exact name match, description relevance, code snippet count, source reputation (High/Medium preferred), and benchmark score (higher is better). If results don't look right, try alternate names or queries (e.g., "next.js" not "nextjs", or rephrase the question). Use version-specific IDs when the user mentions a version
3. `query-docs` with the selected library ID and the user's full question (not single words)
4. Answer using the fetched docs
<!-- context7 -->

<!-- code-authoring-rules -->
## 代码编写规则

对于所有代码任务，在编写或修改代码文件时：

- 必须在必要位置添加必要注释，尤其是复杂逻辑、非直观业务规则、边界处理、兼容性处理和重要实现取舍；避免添加无信息量的机械注释。
- 代码中用于提升用户体验的提示词和面向用户的文案必须使用中文，包括但不限于错误提示、成功提示、加载提示、空状态提示、引导文案、按钮文本、表单标签和占位符。除非用户明确要求其他语言、项目已有规范要求其他语言，或第三方 API、协议、保留字等必须保持原文。
<!-- code-authoring-rules -->

<!-- remote-sync-rules -->

## 执行任务规则

- 每次执行任务（仅仅针对复杂任务，首先进行任务复杂度判定，简单任务直接执行，不走以下规则）需要遵循先制定任务计划，确保执行计划覆盖核心任务（你需要和我沟通确定这个任务的各种细节，也是为了让我为这个项目做整体的 布局规划）---开始按照计划执行---计划执行完成后，调用全新的子Agent去审查你的代码，确保代码没有逻辑和业务上的漏洞
- 执行计划保存在docs\plans文件夹中
- 在审查代码的漏洞时，同样需要先制定修复计划，然后再执行修复计划，修复计划保存在docs\errors中，命名和docs\plans对应的执行计划相近
- 如果修复并没有审查通过，将新发现的问题以追加的方式追加到docs\errors响应的修复计划中
- 命名示例：docs\plans中taskA_plan.md,对应的docs\errors中则命名为：taskA_plan_errors.md


## 代码提交至远程仓库规则
- 每次任务执行完成后，自动将代码提交至远程仓库
- 提交时的备注自行根据此次任务进行简要地说明


## 远程仓库同步规则

当用户要求将代码同步到远程仓库时，必须自动同步到以下两个远程仓库：

- `git@github.com:PHJ20030616/SmartView.git`
- `https://gitee.com/phj20030616/smart-view.git`

执行同步前应检查当前 Git remote 配置；如果缺失对应远程地址，应先补齐或更新 remote，再执行同步。

需要注意的是：在提交之前，项目中是否存在.gitignore文件，避免将不必要的和存在敏感信息的文件上传到远程仓库中



<!-- remote-sync-rules -->

<!-- CODEGRAPH_START -->

## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call - the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely - indexing is the user's decision.
<!-- CODEGRAPH_END -->

<!-- ARCHITECTURE_RULES_START -->
## 架构调用规则

SmartView 项目采用前后端分离架构，包含三个核心服务层，各层之间的调用必须遵循以下规则：

### 调用链规则

1. **React 前端（smartview-web）只能调用 Spring Boot 后端**
   - 前端通过 HTTP/REST API 调用 Spring Boot 提供的 Web API
   - 禁止前端直接调用 FastAPI AI 服务
   - 禁止前端绕过后端直接访问数据库或其他服务

2. **Spring Boot 后端（smartview-backend）调用 FastAPI AI 服务必须通过 `AiServiceClient`**
   - Spring Boot 需要 AI 能力时，必须使用统一的 `AiServiceClient` 客户端
   - 禁止在业务代码中直接使用 RestTemplate 或其他 HTTP 客户端调用 AI 服务
   - `AiServiceClient` 封装了重试、超时、熔断等容错机制

3. **FastAPI AI 服务（smartview-ai）作为独立服务层**
   - 只对 Spring Boot 后端暴露 API
   - 不直接对外提供服务

### 架构边界

```
┌─────────────────┐
│  React 前端     │
│ (smartview-web) │
└────────┬────────┘
         │ HTTP/REST
         ▼
┌─────────────────┐
│ Spring Boot 后端│
│(smartview-      │
│  backend)       │
└────────┬────────┘
         │ AiServiceClient
         ▼
┌─────────────────┐
│  FastAPI AI     │
│ (smartview-ai)  │
└─────────────────┘
```

## 契约优先开发规则

SmartView 项目采用**契约优先**（Contract-First）开发模式，所有跨服务通信必须先定义契约。

### 契约存放规范

1. **HTTP 契约（OpenAPI 3.0）**
   - Spring Boot Web API 契约：`contracts/web-api/openapi.yaml`
   - FastAPI AI API 契约：`contracts/ai-api/openapi.yaml`

2. **消息队列契约（JSON Schema）**
   - MQ 消息契约：`contracts/mq/*.schema.json`
   - 每个消息类型一个独立的 schema 文件

### 契约变更流程

**强制要求：接口变更必须先修改契约文件**

1. 修改契约文件（`contracts/` 目录下的 OpenAPI 或 JSON Schema）
2. 从契约生成代码（DTO、客户端、服务端桩代码）
3. 实现业务逻辑
4. 测试验证

**禁止：** 先写代码再补契约，或者手动维护与契约不一致的代码

### 代码生成规范

1. **禁止手写跨端重复 DTO**
   - 前后端共用的数据模型必须从契约生成
   - 不允许在前端手写与后端重复的类型定义
   - 不允许在 Spring Boot 手写与 FastAPI 重复的 DTO

2. **禁止修改生成目录**
   - 代码生成器输出的目录和文件不可手动修改
   - 生成目录示例：
     - `smartview-backend/src/main/java/com/smartview/generated/`
     - `smartview-web/src/generated/`
     - `smartview-ai/app/generated/`
   - 如需定制，应通过契约或生成器配置实现

3. **生成代码的使用**
   - 业务代码只能引用生成的 DTO/接口，不能复制修改
   - 如生成代码不满足需求，应修改契约而非绕过生成流程

## 测试验收规范

### 任务完成前的测试要求

**强制要求：完成任务前必须说明运行了哪些测试**

在声称任务完成前，必须提供以下信息：

1. **运行的测试类型**
   - 单元测试：哪些类/模块的测试通过
   - 集成测试：哪些接口/契约的集成测试通过
   - 端到端测试：哪些用户场景测试通过

2. **测试覆盖范围**
   - 核心逻辑是否有测试覆盖
   - 边界条件是否测试
   - 错误处理是否验证

3. **测试执行结果**
   - 提供测试命令和输出摘要
   - 测试通过率
   - 如有失败用例，说明原因

### 测试示例说明

在声称任务完成时，必须提供具体的测试证据，而不是模糊的断言。

**✅ 正确示例：**
```
任务完成。已运行以下测试：
1. 单元测试：UserServiceTest 7个用例全部通过
2. 集成测试：/api/users 契约测试通过
3. 手动测试：前端用户列表页面正常展示
测试命令：mvn test
测试通过率：100% (23/23)
```

**❌ 错误示例：**
```
任务完成，应该没问题。
```

**❌ 错误示例：**
```
代码已修改，测试通过。
```
（缺少具体测试内容）

**❌ 错误示例：**
```
功能实现完成，我测试了一下没问题。
```
（缺少测试类型、命令和结果）

## 契约文件组织

各契约目录下必须包含 README.md 说明：

- `contracts/web-api/README.md` — Spring Boot Web API 契约说明
- `contracts/ai-api/README.md` — FastAPI AI API 契约说明
- `contracts/mq/README.md` — 消息队列契约说明

README 应包含：
- 契约用途
- 文件命名规范
- 代码生成命令
- 变更流程

<!-- ARCHITECTURE_RULES_END -->
