# SmartView 简历处理工作流

> 本文档明确简历上传、解析、确认、向量入库、方向选择和画像分析的完整流程与数据一致性保障。

## 1. 整体流程

```mermaid
sequenceDiagram
    autonumber
    actor U as 用户
    participant F as 前端
    participant S as Spring Boot
    participant M as MySQL
    participant O as MinIO
    participant Q as RabbitMQ
    participant A as FastAPI
    participant C as Chroma

    U->>F: 上传 PDF 简历
    F->>S: POST /api/resumes
    S->>M: 创建 resume_file (parse_status=PENDING)
    S->>O: 保存 PDF 文件
    S->>M: 创建 ai_task (type=RESUME_PARSE, status=PENDING)
    S->>Q: 投递 resume_parse_task
    S-->>F: 返回 resumeFileId

    A->>Q: 消费解析任务
    A->>M: 查询 ai_task.task_status，如果已 SUCCESS 则跳过
    A->>O: 读取 PDF
    A->>A: 文本提取 + OCR 兜底
    A->>A: LLM 结构化解析
    A->>Q: 投递 resume_parse_result
    A->>M: 更新 ai_task.task_status=SUCCESS

    S->>Q: 消费解析结果
    S->>M: 查询 ai_task.task_status，如果已 SUCCESS 检查是否已落库
    S->>M: 写入 resume_profile (confirm_status=UNCONFIRMED, version=1)
    S->>M: 更新 resume_file.parse_status=SUCCESS

    U->>F: 查看解析结果
    F->>S: GET /api/resume-profiles/{profileId}
    S-->>F: 返回结构化简历
    U->>F: 确认或编辑简历
    F->>S: PUT /api/resume-profiles/{profileId}
    F->>S: POST /api/resume-profiles/{profileId}/confirm
    S->>M: 更新 confirm_status=CONFIRMED

    Note over S,A: 简历确认后，同步或短链路异步完成向量入库
    S->>M: 创建 ai_task (type=RESUME_VECTORIZE, status=PENDING)
    S->>Q: 投递 resume_vectorize_task
    A->>Q: 消费向量化任务
    A->>M: 查询 ai_task.task_status
    A->>A: 切片简历项目、技能、经历
    A->>C: 写入 resume_profile_chunks（带 user_id, resume_profile_id, profile_version）
    A->>Q: 投递 resume_vectorize_result
    S->>Q: 消费向量化结果
    S->>M: 更新 ai_task.task_status=SUCCESS

    Note over F,S: 向量入库成功后才允许选择面试方向
    U->>F: 选择面试方向（Java 后端 / Agent 开发）
    F->>S: POST /api/profile-analyses
    S->>M: 检查该方向 profile_analysis 是否存在
    alt 已存在且 SUCCESS
        S-->>F: 返回已有分析
    else 不存在或 FAILED
        S->>M: 创建 ai_task (type=PROFILE_ANALYZE, status=PENDING)
        S->>Q: 投递 profile_analyze_task
        A->>Q: 消费画像分析任务
        A->>M: 查询 ai_task.task_status
        A->>C: 检索简历切片（过滤 user_id, resume_profile_id, profile_version）
        A->>C: 检索知识库和面经（过滤 role_direction）
        A->>A: 生成技能标签、项目图谱、风险点、建议主题、阶段目标
        A->>Q: 投递 profile_analyze_result
        S->>Q: 消费画像分析结果
        S->>M: 写入 profile_analysis
        S->>M: 更新 ai_task.task_status=SUCCESS
        S-->>F: 返回分析状态
    end

    Note over F,S: 画像分析成功后才允许开始面试
    U->>F: 点击"开始面试"
    F->>S: POST /api/interview-sessions
    S->>M: 校验 resume_profile.confirm_status=CONFIRMED
    S->>M: 校验 profile_analysis 存在且成功
    S->>M: 创建 interview_session
    S->>A: 请求生成阶段计划和首题
    A-->>S: 返回阶段计划和首题
    S->>M: 更新 stage_plan_json, current_question_id
    S-->>F: 返回首题和进度范围

## 2. 画像分析（方向选择后的内部准备）

> 画像分析是系统内部的面试准备材料：基于已确认简历、简历向量片段和知识/面经
> 检索结果，生成技能标签、项目图谱、能力线索、风险点、建议面试主题和阶段覆盖目标，
> 供后续阶段计划与出题策略使用，不直接面向用户展示。

### 2.1 触发与前置校验

**触发入口：** 前端面试页 `POST /api/profile-analyses`（body：`profileId` + `roleDirection`）。

**前置校验（任一不满足直接返回，不创建任务）：**

| 校验 | 失败响应 |
|------|---------|
| 面试方向必须是 `JAVA_BACKEND` / `AGENT_DEVELOPMENT` | `BAD_REQUEST` |
| 画像存在且属于当前用户 | `NOT_FOUND` / `FORBIDDEN` |
| 画像已确认（`confirm_status=CONFIRMED`） | `409 CONFLICT` |
| **简历向量已成功入库**（存在 `RESUME_VECTORIZE` + `operation=UPSERT` 且 `SUCCESS` 的任务） | `409 CONFLICT` |

向量入库校验是硬性前置：画像分析依赖简历向量片段做语义检索，向量未就绪时
不创建任务，避免前端等待一次注定失败或无上下文的分析。

### 2.2 画像分析任务与结果落库

**幂等与唯一约束：**

- `profile_analysis` 表唯一索引 `(resume_profile_id, role_direction, profile_version)`，
  保证同一简历版本、同一面试方向只有一份有效画像分析；
- `profile_analysis` **只在分析成功时写入一行**，失败/重试由 `ai_task` 承载；
- `POST /api/profile-analyses` 幂等：
  - 已有成功分析 → 直接返回 `SUCCESS`；
  - 已有进行中任务（`PENDING/PROCESSING/RETRYING`）→ 返回任务状态；
  - 分析失败（`FAILED`）→ 创建新 `taskId` 的补偿任务（保留旧任务审计记录）；
- 画像行锁（`SELECT ... FOR UPDATE`）保证并发触发串行化，连续点击只创建一个任务。

**任务链路：**

```mermaid
sequenceDiagram
    autonumber
    participant F as 前端
    participant S as Spring Boot
    participant M as MySQL
    participant Q as RabbitMQ
    participant A as FastAPI
    participant C as Chroma

    F->>S: POST /api/profile-analyses {profileId, roleDirection}
    S->>M: 校验画像已确认 + 向量已入库
    S->>M: 创建 ai_task (type=PROFILE_ANALYZE, PENDING)
    S->>Q: 投递 profile_analyze_task（含 vectorizeCompleted=true）
    A->>Q: 消费画像分析任务
    A->>M: 读取已确认画像
    A->>C: 检索简历切片（user_id + resume_profile_id + profile_version）
    A->>C: 检索八股知识 / 面经案例（过滤 role_direction）
    A->>A: DeepSeek 生成技能标签、项目图谱、风险点、主题、阶段目标
    A->>Q: 投递 profile_analyze_result
    S->>Q: 消费画像分析结果
    S->>M: 按唯一键 upsert profile_analysis
    S->>M: 更新 ai_task=SUCCESS
    S-->>F: 返回分析状态
```

**结果消费（Spring 侧一致性规则）：**

1. 结果消息经 JSON Schema 校验（`profile_analyze_result.schema.json`）；
2. 锁任务行（`FOR UPDATE`），校验 `taskType`、`bizType`、`bizId`、`profileVersion`、
   `roleDirection`、`traceId` 与消息严格匹配，防止伪造或旧版本/错误方向结果污染；
3. 终态任务（`SUCCESS/FAILED`）的重复结果只忽略，不覆盖审计数据；
4. 成功时在任务行锁保护下 upsert `profile_analysis`（并发冲突捕获
   `DuplicateKeyException` 后改为更新已存在行），并更新任务为 `SUCCESS`；
5. 失败只更新任务为 `FAILED`，不写入分析数据。

**错误处理与重试：**

- **FastAPI 侧有界重试**：仅 `LLM_REQUEST_FAILED`、`LLM_INVALID_JSON`、
  `LLM_SCHEMA_INVALID` 等可恢复错误重试（指数退避，最多 3 次）；向量未入库
  （`vectorizeCompleted=false`）、画像不存在、未确认、版本过期属于确定性错误，
  立即回传终态失败；
- **前端轮询**：`GET /api/profile-analyses/{profileId}?roleDirection=`，最多等待 60 秒，
  `SUCCESS` 后才显示"开始面试"按钮；
- **失败重试**：分析失败时前端显示"重试画像分析"（`POST /api/profile-analyses/{profileId}/retry?roleDirection=`），
  重试同样校验向量已入库；**分析未成功前不允许开始面试**；
- **DLQ 收口**：结果消息业务校验失败进入死信队列前，`markResultHandlingFailed`
  将任务收口为 `FAILED`，避免前端永久停留在"处理中"。

