# SmartView 架构优化总结

本文档总结了针对执行计划和优化逻辑中发现的问题及其解决方案。

## 已解决的核心问题

### 1. 职责边界明确化 ✓

**问题：** FastAPI 返回 `nextAction` 建议，Spring Boot "复核"，职责不清。

**解决方案：**
- FastAPI 只返回评估事实（得分、命中点、缺失点、风险点）和候选问题池
- Spring Boot 的 `StagePolicyEngine` 独立决策 `nextAction`
- 详见 `docs/interview-policy.md` 第 1 节

### 2. 候选问题池机制统一 ✓

**问题：** 预生成和追问候选的时机不一致，Redis 丢失时重建规则不明确。

**解决方案：**
- 预生成候选池：提问后后台异步生成（同阶段换题 + 下一阶段入口）
- 追问候选池：回答提交时同步生成（基于回答事实的 0-2 道追问）
- Redis 丢失时从 `candidate_pool_snapshot_json` 重建
- 详见 `docs/interview-policy.md` 第 3 节

### 3. 阶段控制规则确定性 ✓

**问题：** 缺少 `docs/interview-policy.md`，阶段切换逻辑无明确规范。

**解决方案：**
- 创建 `docs/interview-policy.md`，定义 5 条优先级规则
- 规则 1：硬性终止（题量上限、连续低分）
- 规则 2：阶段推进（题量、覆盖度）
- 规则 3：追问深度限制
- 规则 4：候选池为空降级
- 规则 5：正常流程兜底
- 详见 `docs/interview-policy.md` 第 2.4 节

### 4. 简历向量入库时机优化 ✓

**问题：** 异步向量入库可能晚于画像分析，导致检索为空。

**解决方案：**
- 用户确认简历后立即触发向量化任务
- 前端轮询向量入库状态（最多等待 60 秒）
- 向量入库成功后才允许选择面试方向
- 画像分析任务创建时校验向量是否存在
- 详见 `docs/resume-workflow.md` 第 2.1 节

### 5. 幂等性保障强化 ✓

**问题：** `request_id` 生成方、存储位置和与 `version` 的协同不清晰。

**解决方案：**
- `request_id` 由前端生成 UUID，每次点击生成新 ID
- `interview_answer.request_id` 设置唯一索引（数据库层幂等）
- `interview_session.version` 乐观锁防止并发修改会话
- 异步任务通过 `taskId` 查询 `ai_task.task_status` 实现幂等
- 详见 `docs/interview-policy.md` 第 4 节

### 6. 异步任务重试策略明确 ✓

**问题：** 重试间隔、退避策略和死信队列处理流程不明确。

**解决方案：**
- 重试间隔：立即 → 30s → 5min → 30min
- 4xx（除 429）不重试，5xx/429/超时重试
- 超过 `max_retry` 进入死信队列
- Worker 消费前先查询 `ai_task.task_status`，已 `SUCCESS` 则跳过
- 详见 `docs/interview-policy.md` 第 7 节

### 7. LangGraph checkpoint 用途降级 ✓

**问题：** checkpoint 用途不明确，与 MySQL/Redis 恢复的关系混乱。

**解决方案：**
- v1.0 MVP 暂不实现基于 checkpoint 的跨天续面
- 页面刷新恢复基于 MySQL（`current_question_id` + 历史问答）
- Redis 候选池丢失时基于 `candidate_pool_snapshot_json` 重建
- `graph_thread_id` 和 `latest_checkpoint_id` 预留字段，后续版本实现
- 详见 `docs/interview-policy.md` 第 6.3 节

### 8. 画像分析与面试会话时序明确 ✓

**问题：** 用户选择方向的交互入口不明确，画像分析失败时处理不清晰。

**解决方案：**
- 确认简历 → 向量入库等待 → 方向选择 → 画像分析等待 → 面试准备 → 开始面试
- 画像分析失败时允许用户重试，不允许开始面试
- 前端轮询画像分析状态（最多等待 60 秒）
- 详见 `docs/resume-workflow.md` 第 2.2 节

## 新增文档

### `docs/interview-policy.md`
面试策略与执行规范，包含：
- 职责边界（FastAPI vs Spring Boot）
- 阶段定义与切换规则
- 候选问题池机制
- 幂等性与并发控制
- 降级与容错规则
- 状态恢复策略
- 异步任务幂等与重试
- 审计与调试规范

### `docs/resume-workflow.md`
简历处理工作流，包含：
- 完整时序图
- 简历向量入库时机约束
- 画像分析与面试会话创建时机
- 版本一致性保障
- 幂等性保障
- 错误处理与重试

## 需要更新的代码实现

### Phase 5（v0.5）实施时必须遵循：

1. **FastAPI 接口变更**
   - `POST /api/v1/interview/evaluate-answer` 只返回评估事实和追问候选
   - 移除 `nextAction`、`nextStage`、`selectedNextQuestion` 字段

2. **Spring Boot 新增 `StagePolicyEngine`**
   - 实现 `docs/interview-policy.md` 第 2.4 节的 5 条规则
   - 输入：阶段计划、覆盖度、评估事实、候选池
   - 输出：`nextAction`、`nextStage`、`selectedNextQuestionId`、`decisionReason`

3. **数据库索引**
   - `interview_answer.request_id` 唯一索引
   - `resume_profile(user_id, resume_file_id, version)` 唯一索引
   - `profile_analysis(resume_profile_id, role_direction, profile_version)` 唯一索引

4. **Redis Key 规范**
   - 预生成候选池：`interview:candidate_pool:{sessionId}:{questionId}:{currentStage}`
   - TTL: 30 分钟

5. **前端幂等控制**
   - 每次提交回答生成新 UUID 作为 `request_id`
   - 提交时携带 `Idempotency-Key` 或 `X-Request-ID` 请求头

## 验收清单

实施 Phase 5 前必须确认：

- [ ] `docs/interview-policy.md` 已被所有开发者阅读并理解
- [ ] `AGENTS.md` 已引用 `docs/interview-policy.md` 作为面试流程规范
- [ ] FastAPI 接口契约已更新，移除 `nextAction` 等决策字段
- [ ] Spring Boot 已实现 `StagePolicyEngine` 并覆盖单元测试
- [ ] `interview_answer.request_id` 唯一索引已创建
- [ ] `resume_profile.version` 和 `profile_analysis.profile_version` 字段已添加
- [ ] Chroma 向量 metadata 包含 `profile_version`
- [ ] 前端轮询向量入库和画像分析状态的逻辑已实现
- [ ] 异步任务 Worker 查询 `ai_task.task_status` 实现幂等
- [ ] 重试间隔配置为：立即、30s、5min、30min

## 后续优化建议

### v1.1 优化

1. **流式输出支持**
   - 面试问题逐字返回，提升用户体验
   - 评估结果逐项返回（得分 → 命中点 → 缺失点 → 风险点）

2. **候选池预热优化**
   - 在首题返回前预生成第二题候选池
   - 减少首次回答后的等待时间

3. **LangGraph checkpoint 实现**
   - 支持跨天续面
   - 支持面试暂停与恢复

### v1.2 优化

1. **面试计划可调整**
   - 允许用户在面试前预览并调整阶段计划
   - 例如增加项目追问深度、减少八股题量

2. **实时面试指导**
   - 面试过程中显示当前阶段和覆盖进度
   - 提示用户"建议补充 XX 要点"

3. **多轮面试支持**
   - 一份简历支持多次不同方向面试
   - 保留历史面试记录和对比分析

---

## LLM 调用可观测底座（2026-09-11）

**背景**：LLM 调用此前没有任何调用级数据，只有零散的日志行。缺少调用级数据时，
prompt 改动带来的指标变化无法与模型自身波动区分，迭代只能凭感觉。

**变更**

1. `smartview-ai` 侧三份重复的 DeepSeek JSON 调用实现收敛为唯一入口
   `app/services/deepseek_client.py::call_deepseek_json`，由
   `smartview-ai/tests/test_llm_call_single_entry.py` 守护"入口唯一"。
2. 在该单点埋点，把场景、模型、prompt 版本、token 用量、耗时、错误码写入新表
   `llm_call_log`（Flyway V10）。表内不保存 prompt 与响应全文，只有 sha256 与长度。
3. 新增只读查询接口 `GET /api/llm-calls` 与前端观测看板 `/observability`。

**架构边界的显式例外**

`AGENTS.md` 原本规定"FastAPI 不直接写业务主表"。`llm_call_log` 是技术可观测表而非业务主表，
因此被登记为该规则的唯一例外，边界为"只许写 `llm_call_log`，不得读写任何业务表"，
并由 `smartview-ai/tests/test_business_tables_are_never_written.py` 守护。

> 注意：仓库根的 `AGENTS.md` 被 `.gitignore` 忽略（开发机本地规范副本），因此
> **本节是版本控制内可查的权威登记处**，克隆者与 CI 以此为准；`AGENTS.md` 里的同名小节
> 供本地 Agent 阅读。

**尚未完成**

- `biz_type` / `biz_id` 两列已建但未填充：需要按会话归因时再启用，避免为不确定需求预先铺管道。
- `prompt_key` 留空、`prompt_version` 固定为 `p0`：待 Phase 14 引入按场景的 prompt 文件后逐场景填充。

---

**文档版本：** 1.0  
**最后更新：** 2026-09-11  
**维护者：** SmartView 开发团队
