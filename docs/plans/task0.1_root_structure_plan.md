# Task 0.1 — 创建根目录规范文件 执行计划

## 任务目标

创建项目根目录的规范文件和基础目录结构，建立 monorepo 项目的基础框架和 AI 编码工具的行为规范。

## 任务范围

### 1. 追加内容到 AGENTS.md 文件
**目的：** 在现有文件基础上追加架构规则和契约规范

**现有内容：**
- Context7 文档查询规则
- 代码编写规则（注释、中文文案）
- 执行任务规则（计划、审查流程）
- 远程仓库同步规则
- CodeGraph 使用规则

**需要追加的内容要点：**
- 架构调用规则：
  - React 前端只能调用 Spring Boot 后端
  - Spring Boot 调用 FastAPI 必须通过 `AiServiceClient`
- 契约存放规范：
  - HTTP 契约：`contracts/web-api/openapi.yaml`（Spring Boot API）
  - HTTP 契约：`contracts/ai-api/openapi.yaml`（FastAPI API）
  - MQ 契约：`contracts/mq/*.schema.json`
- 代码生成规范：
  - 禁止手写跨端重复 DTO
  - 禁止修改生成目录
  - 接口变更必须先修改契约文件
- 测试验收规范：
  - 完成任务前必须说明运行了哪些测试

### 2. 创建 README.md 文件
**目的：** 说明项目整体结构、模块划分和启动入口

**内容要点：**
- 项目简介：SmartView 智能视图项目
- Monorepo 结构说明
- 各子项目模块介绍：
  - `smartview-web`（React 前端）
  - `smartview-backend`（Spring Boot 后端）
  - `smartview-ai`（FastAPI AI 服务）
  - `smartview-infra`（基础设施配置）
- 启动入口说明
- 目录结构说明
- 契约优先开发流程

### 3. 创建基础目录结构

需要创建的目录：
- `docs/` — 项目文档目录（已存在，需补充子目录）
  - `docs/plans/` — 执行计划（已创建）
  - `docs/errors/` — 错误修复计划（已创建）
  - `docs/architecture/` — 架构设计文档
  - `docs/api/` — API 文档
- `contracts/` — 契约定义目录
  - `contracts/web-api/` — Spring Boot HTTP 契约
  - `contracts/ai-api/` — FastAPI HTTP 契约
  - `contracts/mq/` — 消息队列契约
- `knowledge/` — 知识库目录
- `smartview-infra/` — 基础设施配置目录

## 执行步骤

### 步骤 1：追加架构规则到 AGENTS.md 并同步到 .claude/CLAUDE.md
1. 读取 AGENTS.md 和 .claude/CLAUDE.md 当前内容，确认追加位置和格式
2. 确认两个文件已在 Git 版本控制中（如未初始化 Git，先初始化）
3. 在 AGENTS.md 文件末尾追加架构规则章节
4. 明确架构边界和调用规则
5. 定义契约优先的开发流程
6. 说明禁止项和强制要求
7. **将完全相同的架构规则追加到 `.claude/CLAUDE.md`**，确保 CodeX 和 Claude Code 都遵循同一套规范

### 步骤 2：创建 README.md
1. 编写项目概述（## 项目简介）
2. 说明 monorepo 结构（## Monorepo 结构）
3. 列出各模块职责（## 项目模块）
4. 提供启动指南（## 快速开始）
5. 说明目录结构（## 目录结构）
6. 说明契约优先开发流程（## 契约优先开发）

### 步骤 3：创建基础目录结构
1. 检查 .gitignore 文件是否存在，如不存在则创建并配置必要的忽略规则
2. 创建 `docs/` 子目录（architecture/、api/）
3. 创建 `contracts/` 及其子目录（web-api/、ai-api/、mq/）
4. 在 contracts 各子目录创建 README.md 说明契约用途和文件命名规范
5. 创建 `knowledge/` 目录
6. 创建 `smartview-infra/` 目录

### 步骤 4：验证
1. 确认根目录结构清晰
2. 确认 AGENTS.md 规则完整
3. 确认 .claude/CLAUDE.md 规则完整
4. **对比 AGENTS.md 和 .claude/CLAUDE.md 追加内容的一致性**
5. 确认 README.md 描述准确且章节结构完整
6. 确认 .gitignore 配置合理

## 验收标准

- [ ] `AGENTS.md` 文件已追加架构规则，包含所有必需规则
- [ ] `.claude/CLAUDE.md` 已追加相同的架构规则
- [ ] **AGENTS.md 和 .claude/CLAUDE.md 中新增的架构规则内容完全一致**
- [ ] `.gitignore` 文件存在且包含必要的忽略规则
- [ ] `README.md` 文件已创建，包含完整章节结构，清晰说明 monorepo 结构
- [ ] `docs/`、`contracts/`、`knowledge/`、`smartview-infra/` 目录已创建
- [ ] 契约目录结构完整（`contracts/web-api/`、`contracts/ai-api/`、`contracts/mq/`）
- [ ] contracts 各子目录包含 README.md 说明契约用途
- [ ] 根目录能清楚看出 monorepo 结构
- [ ] AI 工具（CodeX 和 Claude Code）读取规则后能知道禁止项和契约规则

## 潜在风险

1. **规则遗漏：** AGENTS.md 可能遗漏重要的架构约束
   - **缓解措施：** 按照任务要求逐项检查必须包含的规则

2. **目录结构不清晰：** 目录命名或层级可能不符合 monorepo 最佳实践
   - **缓解措施：** 参考标准 monorepo 项目结构

3. **契约路径不明确：** 契约文件的存放位置可能引起混淆
   - **缓解措施：** 在 AGENTS.md 和 README.md 中明确说明

## 依赖项

- 无外部依赖
- 本任务为项目初始化任务，不依赖其他任务

## 后续任务

- Task 0.2 及后续任务将基于本任务创建的目录结构展开
- 契约文件将在后续任务中填充具体内容
- **重要：** 后续修改架构规则时必须同步更新 AGENTS.md 和 .claude/CLAUDE.md，保持两者一致

## 测试计划

本任务为文档和目录创建任务，测试方式为：
1. 检查所有文件是否创建
2. 检查目录结构是否完整
3. 检查 AGENTS.md 规则是否完整
4. 检查 .claude/CLAUDE.md 规则是否完整
5. **检查 AGENTS.md 和 .claude/CLAUDE.md 追加内容一致性**（使用 diff 或逐行对比）
6. 检查 README.md 是否清晰描述项目结构，章节是否完整
7. 检查 contracts 各子目录是否包含 README 说明契约用途
8. 检查 .gitignore 配置合理性（包含 node_modules/、.env、target/、*.class 等常见忽略项）
