# Task 1.3 / 1.4 AI 服务与前端初始化审查修复计划

## 审查结论

子 Agent 代码审查未发现 Critical 或 High 级问题，发现 6 个 Medium 级问题。修复范围仅覆盖本次新建的 `smartview-ai/`、`smartview-web/` 和任务文档，不修改既有跨服务 OpenAPI 契约。

## 问题与修复方案

- [x] Fix 1: 对齐 FastAPI 健康检查 OpenAPI
  - 为健康检查定义 Pydantic 响应模型，声明 `status` 枚举和 `timestamp` 的 `date-time` 格式。
  - 将接口 `operation_id` 设置为契约中的 `aiHealthCheck`。
  - 增加测试校验 FastAPI `/openapi.json` 中的 operationId、必填字段、枚举和时间格式。

- [x] Fix 2: 对齐 AI 服务错误响应并补齐 traceId
  - 错误体改为契约定义的 `error`、`message`、`traceId`。
  - 增加请求级 traceId 中间件：接收合法 UUID 请求头，否则生成新 UUID，并写入响应头。
  - 异常处理统一从请求状态读取 traceId，确保错误响应可关联服务端日志。
  - 增加测试覆盖错误体和 `X-Trace-Id` 响应头。

- [x] Fix 3: 避免前端构建产生未忽略的 TypeScript 文件
  - 将构建脚本改为先运行无输出类型检查，再执行 Vite 构建。
  - 删除本次构建生成的 `vite.config.js`、`vite.config.d.ts` 和 `tsconfig*.tsbuildinfo`。

- [x] Fix 4: 避免 README 引导创建未忽略的虚拟环境
  - 将 AI 服务 README 中的虚拟环境目录改为仓库已忽略的 `venv/`。

- [x] Fix 5: 保证前端 traceId 降级值符合 UUID 约束
  - 在 `crypto.randomUUID()` 不可用时生成符合 RFC 4122 v4 格式的 UUID。
  - 增加测试验证 UUID 格式以及降级路径。

- [x] Fix 6: 固化前端依赖解析结果
  - 将已生成的 `smartview-web/package-lock.json` 强制纳入 Git 跟踪。
  - 不修改用户已有的 `.gitignore` 改动；锁文件一旦被跟踪，后续更新仍会被 Git 识别。

- [x] Fix 7: 完善 AI 错误响应契约
  - 将 `BadRequest` 和 `InternalServerError` 响应中的 `traceId` 加入必填字段。
  - 保持实现与契约一致，确保跨服务错误响应始终可关联链路日志。

## 验证命令

- `cd smartview-ai && python -m pytest`
- `cd smartview-web && npm run typecheck`
- `cd smartview-web && npm run test`
- `cd smartview-web && npm run build`
- 启动 FastAPI 并访问 `/api/v1/health`、`/docs`、`/openapi.json`
- 启动 Vite 并访问 `/`、`/login`、`/resume`、`/interview`、`/report`
