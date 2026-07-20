# 文档整合完成总结

## 已完成的文档整合

### 1. README.md 更新

✅ **新增核心文档引用**
- 在"当前仓库处于 v1.0 规划..."部分添加了三个核心规范文档的引用
- `docs/interview-policy.md`：面试策略与执行规范
- `docs/resume-workflow.md`：简历处理工作流
- `docs/architecture-improvements.md`：架构优化总结

✅ **技术选型说明优化**
- 添加了关键职责分离说明
- 明确 Spring Boot 负责阶段决策（`StagePolicyEngine`）
- 明确 FastAPI 只返回评估事实和候选问题
- 引用 `docs/interview-policy.md` 第 1 节

✅ **面试阶段控制策略优化**
- 移除了"LLM 可以给出 nextAction 建议，但需要校验"的描述
- 更新为"FastAPI 只返回评估事实和候选问题池"
- 明确 `StagePolicyEngine` 的 5 条优先级决策规则
- 引用 `docs/interview-policy.md` 第 2.4 节

✅ **文档索引完善**
- 将新增的三个核心规范文档标记为"必读"
- 保留原有的后续补充文档建议

### 2. smartview-task-plan_1.0.md 更新

✅ **顶部引用区域**
- 在"详细设计文档"下方新增"核心规范文档（实施前必读）"区域
- 列出三个核心规范文档及其用途说明

✅ **Task 3.6 — 简历切片向量入库**
- 明确"用户确认后立即创建任务"
- 添加"前端轮询向量入库状态（最多 60 秒）"
- 添加"成功后才显示方向选择按钮"
- 添加"向量入库失败允许重试，降级使用 MySQL"
- 引用 `docs/resume-workflow.md` 第 2.1 节

✅ **Task 4.3 — 画像分析任务**
- 明确"向量入库成功后才能选择方向"
- 添加"画像分析任务创建前校验向量是否已入库"
- 添加"前端轮询画像分析状态（最多 60 秒）"
- 添加"成功后才显示开始面试按钮"
- 添加"失败时允许重试，不允许开始面试"
- 引用 `docs/resume-workflow.md` 第 2.2 节

✅ **Task 5.3 — 候选问题池生成**
- 区分"预生成候选池（后台异步）"和"追问候选池（回答时同步）"
- 明确 Redis key 格式和 TTL（30 分钟）
- 明确"只提供备选问题，由 StagePolicyEngine 决定下一步"
- 添加"Redis 丢失时从快照重建"
- 引用 `docs/interview-policy.md` 第 3 节

✅ **Task 5.4 — 提交回答与下一题选择**
- 明确"request_id 由前端生成 UUID"
- 明确"request_id 设置唯一索引保证幂等"
- 移除"FastAPI 返回 nextAction 建议"
- 更新为"FastAPI 只返回评估事实和追问候选池"
- 明确"StagePolicyEngine 独立决策 nextAction"
- 添加"保存决策快照到 candidate_pool_snapshot_json"
- 引用 `docs/interview-policy.md` 第 2.4 节（决策规则）、第 4 节（幂等性）

✅ **关键文件列表更新**
- Task 5.4 新增 `StagePolicyEngine.java`
- 移除 `stage_controller.py` 和 `select_next_question.py`（职责已转移到 Spring Boot）

## 文档一致性保障

### 核心概念统一

1. **职责边界**
   - README.md：技术选型说明中明确
   - smartview-task-plan_1.0.md：Task 5.3、5.4 中体现
   - interview-policy.md：第 1 节详细定义

2. **候选问题池机制**
   - README.md：面试阶段控制策略中提及
   - smartview-task-plan_1.0.md：Task 5.3 详细说明
   - interview-policy.md：第 3 节完整规范

3. **简历处理流程**
   - README.md：业务主流程图中体现
   - smartview-task-plan_1.0.md：Task 3.6、4.3 中实施
   - resume-workflow.md：完整时序图和约束

4. **幂等性保障**
   - smartview-task-plan_1.0.md：Task 5.4 中说明
   - interview-policy.md：第 4 节详细规范

### 文档引用关系

```
README.md
  ├─ 引用 docs/interview-policy.md（职责边界、阶段控制）
  ├─ 引用 docs/resume-workflow.md（简历流程）
  └─ 引用 docs/architecture-improvements.md（优化总结）

smartview-task-plan_1.0.md
  ├─ 顶部引用三个核心规范文档
  ├─ Task 3.6 引用 resume-workflow.md 第 2.1 节
  ├─ Task 4.3 引用 resume-workflow.md 第 2.2 节
  ├─ Task 5.3 引用 interview-policy.md 第 3 节
  └─ Task 5.4 引用 interview-policy.md 第 2.4 节、第 4 节

docs/interview-policy.md
  └─ 被 README.md 和 smartview-task-plan_1.0.md 引用

docs/resume-workflow.md
  └─ 被 smartview-task-plan_1.0.md 引用

docs/architecture-improvements.md
  └─ 被 README.md 引用
```

## 开发者必读流程

开发者在实施 Phase 5（v0.5 模拟面试主流程）前，应按以下顺序阅读：

1. **README.md** - 了解整体架构和职责分离
2. **docs/interview-policy.md** - 理解面试策略和决策规则（必读，15KB）
3. **docs/resume-workflow.md** - 理解简历处理时序和约束
4. **smartview-task-plan_1.0.md** - 查看具体实施任务
5. **docs/architecture-improvements.md** - 了解已解决的问题和待实施清单

## 验收清单

实施前确认：

- [x] README.md 已更新，引用核心规范文档
- [x] README.md 明确职责分离和决策机制
- [x] smartview-task-plan_1.0.md 顶部添加核心规范引用
- [x] Task 3.6 更新向量入库时机和轮询逻辑
- [x] Task 4.3 更新画像分析时序和校验逻辑
- [x] Task 5.3 更新候选池分类和 Redis 机制
- [x] Task 5.4 更新职责边界和幂等性保障
- [x] 所有任务引用对应的规范文档章节
- [x] 文档间引用关系清晰，无循环依赖

---

**整合完成时间：** 2026-07-16  
**维护者：** SmartView 开发团队
