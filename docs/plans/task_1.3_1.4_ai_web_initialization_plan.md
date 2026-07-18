# Task 1.3 / 1.4 AI 服务与前端工程初始化执行计划

## 目标

在仓库中初始化两个独立子工程：

- `smartview-ai/`：提供 FastAPI AI 服务基础骨架，包含配置、日志、错误处理、Pydantic schema 和 `/api/v1/health`。
- `smartview-web/`：提供 React + Ant Design 前端基础骨架，包含路由、请求封装、基础布局、登录页、首页以及简历、面试、报告页面目录。

> 前置门禁：本计划需要经过全新子 Agent 审查。若审查发现逻辑或业务漏洞，先修订计划，再进入编码。

## 技术选型依据

- FastAPI：采用官方推荐的 `FastAPI` 应用实例、APIRouter 和 `TestClient` + pytest 验证方式。
- Pydantic：使用 `pydantic-settings` 管理环境变量配置，避免业务代码散落读取环境变量。
- React 前端：使用 Vite + TypeScript 初始化，满足快速本地启动和 `npm run typecheck`。
- Ant Design：使用 `ConfigProvider`、`App`、`Layout`、`Menu`、`Card`、`Button`、`Form` 等组件搭建基础页面。
- React Router：使用浏览器路由承载登录、首页、简历、面试和报告页面。

## 文件结构

- Create: `smartview-ai/pyproject.toml`
- Create: `smartview-ai/README.md`
- Create: `smartview-ai/app/__init__.py`
- Create: `smartview-ai/app/main.py`
- Create: `smartview-ai/app/api/__init__.py`
- Create: `smartview-ai/app/api/v1/__init__.py`
- Create: `smartview-ai/app/api/v1/router.py`
- Create: `smartview-ai/app/api/v1/health.py`
- Create: `smartview-ai/app/core/__init__.py`
- Create: `smartview-ai/app/core/config.py`
- Create: `smartview-ai/app/core/logging.py`
- Create: `smartview-ai/app/core/errors.py`
- Create: `smartview-ai/app/schemas/__init__.py`
- Create: `smartview-ai/app/schemas/common.py`
- Create: `smartview-ai/app/generated/README.md`
- Create: `smartview-ai/app/schemas/internal.py`
- Create: `smartview-ai/tests/__init__.py`
- Create: `smartview-ai/tests/test_health.py`
- Create: `smartview-web/package.json`
- Create: `smartview-web/README.md`
- Create: `smartview-web/index.html`
- Create: `smartview-web/tsconfig.json`
- Create: `smartview-web/tsconfig.node.json`
- Create: `smartview-web/vite.config.ts`
- Create: `smartview-web/src/main.tsx`
- Create: `smartview-web/src/app/App.tsx`
- Create: `smartview-web/src/app/router.tsx`
- Create: `smartview-web/src/app/layouts/MainLayout.tsx`
- Create: `smartview-web/src/api/http.ts`
- Create: `smartview-web/src/api/generated/README.md`
- Create: `smartview-web/src/pages/login/LoginPage.tsx`
- Create: `smartview-web/src/pages/home/HomePage.tsx`
- Create: `smartview-web/src/pages/resume/ResumePage.tsx`
- Create: `smartview-web/src/pages/interview/InterviewPage.tsx`
- Create: `smartview-web/src/pages/report/ReportPage.tsx`
- Create: `smartview-web/src/styles/global.css`
- Create when needed: `docs/errors/task_1.3_1.4_ai_web_initialization_plan_errors.md`

## 实施步骤

- [x] Step 1: 创建 FastAPI 工程骨架
  - 建立 `smartview-ai/app`、`app/api/v1`、`app/core`、`app/schemas`、`tests`。
  - `pyproject.toml` 定义运行依赖、测试依赖和 pytest 配置。
  - `README.md` 说明启动、测试和 OpenAPI 查看方式。

- [x] Step 2: 实现 AI 服务配置、日志和错误处理
  - `Settings` 统一管理应用名、环境、API 前缀、日志级别和 CORS 来源。
  - 日志配置集中在 `app/core/logging.py`，后续 AI 调用和任务处理复用。
  - `app/core/errors.py` 注册业务异常、请求校验异常和兜底异常处理。
  - 面向调用方的错误提示使用中文，详细异常只进入服务端日志。
  - `app/schemas/` 只放服务内部 schema，不手写跨服务 DTO；后续跨服务请求/响应模型必须从 `contracts/ai-api/openapi.yaml` 生成到 `app/generated/`。

- [x] Step 3: 实现 `/api/v1/health`
  - 健康检查严格对齐 `contracts/ai-api/openapi.yaml`，只返回 `status`、`timestamp`。
  - 该接口不依赖数据库、中间件、模型或外部服务，确保基础骨架可独立验收。
  - `app/main.py` 创建 FastAPI 应用、挂载 v1 路由、注册异常处理和中间件。

- [x] Step 4: 编写 AI 服务测试
  - 使用 FastAPI `TestClient` 验证 `/api/v1/health` 返回 200 和 `UP`。
  - 验证 `/docs` 可访问，确保 OpenAPI 文档入口存在。
  - 运行 `python -m pytest` 作为测试框架可运行证明。

- [x] Step 5: 创建 React 前端工程骨架
  - 使用 Vite + React + TypeScript 的文件结构和脚本。
  - 引入 Ant Design、React Router 和 axios。
  - 建立 `src/app`、`src/api`、`src/api/generated`、`src/pages/*`。

- [x] Step 6: 实现路由、请求封装和基础页面
  - 路由包含 `/login`、`/`、`/resume`、`/interview`、`/report`。
  - 基础布局使用 Ant Design `Layout` 和导航菜单，页面文案全部使用中文。
  - 登录页提供基础表单与演示跳转，首页提供任务入口。
  - 简历、面试、报告页面先提供后续功能承载骨架。
  - 请求封装在请求头自动携带 `X-Trace-Id`，并从响应头或响应体更新当前 traceId。
  - `src/api/generated/README.md` 明确该目录只能由 OpenAPI 生成工具写入。
  - 把 traceId 生成、读取、更新逻辑拆成可测试函数，避免只靠人工阅读验收。

- [x] Step 7: 前端类型检查与启动验证
  - 运行 `npm install` 安装依赖。
  - 运行 `npm run typecheck`。
  - 运行前端单元测试，覆盖请求头写入和响应头/响应体 traceId 更新逻辑。
  - 运行 `npm run build` 验证 Vite 生产构建。
  - 启动本地开发服务并访问首页，验证首页能渲染。

- [x] Step 8: 子 Agent 代码审查与修复
  - 实现完成后调用全新子 Agent 审查本次新增代码。
  - 如果发现问题，先写入 `docs/errors/task_1.3_1.4_ai_web_initialization_plan_errors.md` 修复计划，再执行修复。
  - 修复后重新运行受影响测试和验收命令；二次复审已确认健康接口、错误 traceId、UUID 降级、构建产物和锁文件处理。

- [x] Step 9: 提交并同步远程
  - 检查 `.gitignore`，确保依赖目录、构建产物、虚拟环境和敏感配置不会被提交。
  - 只暂存本任务新增/修改文件。
  - 使用简短任务说明提交。
  - 检查并补齐 GitHub、Gitee remote，然后推送到两个远程仓库。

## 验收标准映射

- FastAPI 能启动：通过 `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000` 验证。
- `/api/v1/health` 返回成功：通过 pytest 和本地 HTTP 请求验证。
- `/docs` 可查看 OpenAPI：通过 pytest 和本地 HTTP 请求验证。
- Python 测试框架可运行：通过 `python -m pytest` 验证。
- 前端能启动：首页通过 Vite 开发服务访问验证。
- 首页能访问：通过浏览器或 HTTP 请求验证 Vite 首页。
- 请求封装 traceId：通过前端单元测试验证请求头写入和响应 traceId 更新。
- `npm run typecheck` 可执行：通过实际命令验证。

## 风险与边界

- 本任务只初始化服务和页面骨架，不实现真实登录、简历上传、面试流程或报告生成业务。
- 前端遵守架构边界，只封装面向 Spring Boot 后端的 REST 请求，不直接调用 FastAPI AI 服务。
- `src/api/generated/` 只提供占位 README，不手写生成模型或客户端。
- AI 服务健康检查严格对齐 `contracts/ai-api/openapi.yaml` 的 `/api/v1/health`，其它 AI 能力接口后续再按契约生成代码并实现。
