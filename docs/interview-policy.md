# SmartView 面试策略与执行规范

> 本文档是面试阶段覆盖、下一步动作决策、候选题排序、单轮恢复和降级规则的事实来源。所有涉及面试流程控制的代码必须遵循本规范。

## 1. 职责边界

### 1.1 FastAPI 职责

FastAPI + LangGraph 负责 AI 能力，**只返回评估事实和候选问题**，不做业务决策：

**返回内容：**
- 回答评估事实：得分、等级、命中要点、缺失要点、风险点
- 追问候选池（0-2 道）：基于当前回答事实、缺口或矛盾生成的追问题
- 候选池元信息：每道题的来源、考察点、预期要点

**禁止返回：**
- `nextAction`（FOLLOW_UP / SWITCH_TOPIC / NEXT_STAGE / FINISH）
- `nextStage`（下一阶段名称）
- `selectedNextQuestion`（最终选中的下一题）

### 1.2 Spring Boot 职责

Spring Boot 的 `StagePolicyEngine` 负责确定性业务决策：

**输入：**
- 会话的 `stage_plan_json`（阶段计划）
- 会话的 `stage_coverage_json`（阶段覆盖度）
- FastAPI 返回的评估事实
- Redis 中的预生成候选池（提问后后台生成）
- FastAPI 返回的追问候选池（基于当前回答生成）

**输出：**
- `nextAction`：FOLLOW_UP / SWITCH_TOPIC / NEXT_STAGE / FINISH
- `nextStage`：如果切阶段，指定下一阶段名称
- `selectedNextQuestionId`：最终选中的下一题 ID
- `decisionReason`：决策原因，用于审计和调试

**决策流程：**
1. 合并预生成候选池和追问候选池
2. 检查阶段计划约束（题量、追问深度、覆盖度）
3. 应用确定性规则（见第 2 节）
4. 选择候选池中符合规则的题目
5. 持久化决策快照到 `answer_evaluation.candidate_pool_snapshot_json`

## 2. 阶段定义与切换规则

### 2.1 阶段定义

| 阶段 | 英文标识 | 目标 | 题型 |
| --- | --- | --- | --- |
| 八股基础 | `BASIC` | 考察基础知识扎实度 | 概念、原理、对比、场景判断 |
| 项目追问 | `PROJECT` | 考察项目经历真实性和深度 | 技术选型、架构、难点、优化 |
| 场景设计 | `SCENARIO` | 考察综合应用和设计能力 | 方案设计、权衡、落地、改进 |

### 2.2 阶段计划结构

`interview_session.stage_plan_json` 必须包含以下字段：

```json
{
  "policy_version": "1.0",
  "total_min_questions": 8,
  "total_max_questions": 20,
  "stages": [
    {
      "stage": "BASIC",
      "min_questions": 3,
      "max_questions": 8,
      "required_topics": ["并发", "JVM", "Spring"],
      "max_follow_up_depth": 2
    },
    {
      "stage": "PROJECT",
      "min_questions": 2,
      "max_questions": 6,
      "required_topics": ["简历项目1", "简历项目2"],
      "max_follow_up_depth": 3
    },
    {
      "stage": "SCENARIO",
      "min_questions": 2,
      "max_questions": 6,
      "required_topics": ["系统设计", "性能优化"],
      "max_follow_up_depth": 2
    }
  ]
}
```

### 2.3 阶段覆盖度结构

`interview_session.stage_coverage_json` 记录当前覆盖情况：

```json
{
  "BASIC": {
    "question_count": 5,
    "covered_topics": ["并发", "JVM"],
    "missing_topics": ["Spring"],
    "current_topic_follow_up_count": 2
  },
  "PROJECT": {
    "question_count": 0,
    "covered_topics": [],
    "missing_topics": ["简历项目1", "简历项目2"],
    "current_topic_follow_up_count": 0
  },
  "SCENARIO": {
    "question_count": 0,
    "covered_topics": [],
    "missing_topics": ["系统设计", "性能优化"],
    "current_topic_follow_up_count": 0
  }
}
```

### 2.4 阶段切换规则（确定性）

`StagePolicyEngine` 按以下优先级决策：

#### 规则 1：硬性终止条件（最高优先级）
- 题量达到 `total_max_questions`：立即 `FINISH`
- 用户主动结束：立即 `FINISH`
- 连续 3 题评估失败（得分 < 30 且无有效要点）：`FINISH`，`end_reason = QUALITY_TOO_LOW`

#### 规则 2：阶段推进条件
- 当前阶段题量达到 `max_questions`：必须 `NEXT_STAGE`
- 当前阶段 `required_topics` 全部覆盖 且 题量 >= `min_questions`：可以 `NEXT_STAGE`
- 所有阶段都满足推进条件 且 总题量 >= `total_min_questions`：`FINISH`

#### 规则 3：主题追问深度限制
- `current_topic_follow_up_count` 达到 `max_follow_up_depth`：禁止 `FOLLOW_UP`，只能 `SWITCH_TOPIC` 或 `NEXT_STAGE`

#### 规则 4：候选池为空的降级
- 追问候选为空 且 同阶段换题候选为空：强制 `NEXT_STAGE`
- 下一阶段候选也为空：`FINISH`，`end_reason = NO_VALID_QUESTION`

#### 规则 5：正常流程（兜底）
- 回答质量好（得分 >= 70）且有追问候选 且未达深度上限：`FOLLOW_UP`
- 回答质量中等（40 <= 得分 < 70）且同阶段有换题候选：`SWITCH_TOPIC`
- 回答质量差（得分 < 40）且同阶段有换题候选：`SWITCH_TOPIC`
- 其他情况：`NEXT_STAGE`

## 3. 候选问题池机制

### 3.1 候选池分类

| 类型 | 生成时机 | 数量 | 用途 |
| --- | --- | --- | --- |
| 预生成候选池 | 提问后后台异步生成 | 2-4 道 | 低延迟路径、AI 超时兜底 |
| 追问候选池 | 回答提交时同步生成 | 0-2 道 | 基于回答事实的深度追问 |

### 3.2 预生成候选池内容

**包含：**
- 同阶段换题候选（1-2 道）：覆盖当前阶段未覆盖的 `required_topics`
- 下一阶段入口候选（1-2 道）：下一阶段的典型开场题

**存储：**
- Redis key: `interview:candidate_pool:{sessionId}:{questionId}:{currentStage}`
- TTL: 30 分钟
- 内容：JSON 数组，每道题包含 `question_text`、`stage`、`topic`、`candidate_type`、`expected_points`

### 3.3 追问候选池内容

**包含：**
- 基于回答缺口的追问（0-1 道）：针对 `missing_points` 补充提问
- 基于回答矛盾的追问（0-1 道）：针对 `risk_points` 澄清提问

**生成规则：**
- 回答得分 < 40：不生成追问候选（质量太低，追问无意义）
- 回答得分 40-70：生成 1 道缺口追问
- 回答得分 > 70 且有亮点：生成 1 道深度追问

### 3.4 候选池合并与排序

`StagePolicyEngine` 按以下优先级排序候选池：

1. **追问候选**（如果未达深度上限）
2. **同阶段换题候选**（优先覆盖 `missing_topics`）
3. **下一阶段入口候选**（如果当前阶段满足推进条件）

### 3.5 候选池缺失时的重建

**触发条件：**
- Redis 中找不到对应 key
- Redis 中的候选池 TTL 过期
- 候选池 JSON 解析失败

**重建流程：**
1. 读取 `interview_session` 的 `current_question_id`、`stage_plan_json`、`stage_coverage_json`
2. 读取最近一次 `answer_evaluation.candidate_pool_snapshot_json`
3. 如果快照存在且创建时间 < 5 分钟，直接使用快照
4. 否则，调用 FastAPI 重新生成候选池（传入当前阶段、覆盖度、历史问题）
5. 写入 Redis 并返回

## 4. 幂等性与并发控制

### 4.1 回答提交幂等

**机制：**
- 前端每次点击"提交"生成新的 UUID 作为 `request_id`
- `interview_answer.request_id` 设置唯一索引
- 后端先查询 `interview_answer` 表，如果 `request_id` 已存在，直接返回对应的下一题

**实现：**
```sql
SELECT id, question_id FROM interview_answer 
WHERE request_id = ? AND deleted = 0;
```

如果存在，读取对应的 `answer_evaluation.selected_next_question_id`，返回该题。

### 4.2 会话并发控制

**机制：**
- `interview_session.version` 作为乐观锁版本号
- 更新会话时必须校验版本号

**实现：**
```sql
UPDATE interview_session 
SET current_question_id = ?, 
    question_count = question_count + 1, 
    stage_coverage_json = ?,
    version = version + 1,
    updated_at = NOW()
WHERE id = ? AND version = ?;
```

如果影响行数为 0，说明版本冲突，拒绝本次更新。

### 4.3 `request_id` 与 `version` 的协同

| 场景 | `request_id` | `version` | 处理 |
| --- | --- | --- | --- |
| 正常提交 | 新 UUID | 匹配 | 正常处理 |
| 重复点击 | 相同 UUID | 匹配或不匹配 | 返回已有结果 |
| 并发提交不同答案 | 不同 UUID | 第二个不匹配 | 第二个被拒绝 |
| 提交过期题目 | 新 UUID | 匹配但 `question_id` 不匹配 | 拒绝 |

## 5. 降级与容错规则

### 5.1 回答质量降级

| 场景 | 降级策略 |
| --- | --- |
| 回答"不会" | 降低追问深度，切换到同阶段其他主题 |
| 连续 2 题得分 < 40 | 不再追问，切换主题或切阶段 |
| 连续 3 题得分 < 30 | 提前结束面试，生成阶段性报告 |

### 5.2 AI 服务降级

| 场景 | 降级策略 |
| --- | --- |
| FastAPI 超时（> 10s） | 使用 Redis 预生成候选池 |
| Redis 候选池缺失 | 基于 `candidate_pool_snapshot_json` 重建 |
| 快照也不存在 | 调用 FastAPI 重新生成（增加超时时间到 30s） |
| 仍然失败 | 返回错误，允许用户重试 |

### 5.3 候选池为空降级

| 场景 | 降级策略 |
| --- | --- |
| 追问候选为空 | 使用同阶段换题候选 |
| 同阶段换题候选也为空 | 使用下一阶段入口候选 |
| 所有候选都为空 | 结束面试，生成报告 |

## 6. 状态恢复策略

### 6.1 页面刷新恢复

**场景：** 用户刷新浏览器或重新打开页面

**恢复流程：**
1. 前端调用 `GET /api/interview-sessions/{sessionId}`
2. 后端返回：
   - `current_question_id`：当前待回答问题
   - `status`：会话状态
   - `question_count`：已提问数量
   - `expected_min_questions` / `expected_max_questions`：进度范围
3. 前端调用 `GET /api/interview-sessions/{sessionId}/history`
4. 后端返回历史问答列表（question + answer + evaluation）

**权威数据源：** MySQL

### 6.2 Redis 候选池丢失恢复

见 3.5 节。

### 6.3 LangGraph checkpoint 恢复（v1.0 暂不实现）

**预留字段：**
- `interview_session.graph_thread_id`
- `interview_session.latest_checkpoint_id`

**v1.0 行为：**
- 创建会话时记录 `graph_thread_id`（LangGraph 自动生成）
- 不实现基于 checkpoint 的跨天续面
- 所有状态恢复基于 MySQL + Redis

**后续版本：**
- 用户跨天或长时间中断后继续面试
- 基于 `graph_thread_id` 和 `latest_checkpoint_id` 恢复 LangGraph 状态
- 重建候选池和阶段覆盖度

## 7. 异步任务幂等与重试

### 7.1 任务幂等保障

**机制：**
1. Spring Boot 在事务内创建 `ai_task` 记录，状态为 `PENDING`
2. 事务提交后，投递 MQ 消息（包含 `taskId`）
3. FastAPI Worker 消费消息后，先查询 `ai_task.task_status`
4. 如果已 `SUCCESS`，直接 ACK 跳过
5. 如果 `PENDING` 或 `RETRYING`，执行任务
6. 执行成功后，先投递结果消息，再更新 `ai_task.task_status` 为 `SUCCESS`
7. Spring Boot 消费结果消息时，先查询 `ai_task.task_status`
8. 如果已 `SUCCESS`，检查对应业务表是否已写入，避免重复落库

### 7.2 重试策略

| 重试次数 | 间隔 | 说明 |
| --- | --- | --- |
| 第 1 次 | 立即 | 网络抖动或临时错误 |
| 第 2 次 | 30 秒 | 短期故障恢复 |
| 第 3 次 | 5 分钟 | AI 服务限流或负载高 |
| 第 4 次 | 30 分钟 | 长时间故障 |
| 超过 `max_retry` | - | 进入死信队列，人工介入 |

**重试判断：**
- 4xx 错误（除 429）：不重试，直接标记 `FAILED`
- 429 限流：重试
- 5xx 错误：重试
- 网络超时：重试
- 业务逻辑错误（例如简历格式无法解析）：不重试，标记 `FAILED`

### 7.3 死信队列处理

**进入条件：**
- 重试次数超过 `max_retry`
- 消息格式不符合 JSON Schema
- `taskId` 在数据库中不存在

**处理流程：**
1. 消息进入死信队列
2. 后台监控任务定期扫描死信队列
3. 记录到日志或告警系统
4. 人工排查并决定是否重新投递

## 8. 版本管理

### 8.1 策略版本

`stage_plan_json.policy_version` 标识策略版本，当前为 `1.0`。

**版本变更时：**
- 新版本策略发布后，新创建的会话使用新版本
- 正在进行的会话继续使用创建时的版本
- `StagePolicyEngine` 根据 `policy_version` 路由到对应策略实现

### 8.2 阶段计划版本

`interview_session.stage_plan_json` 在会话创建时生成，后续不再修改。

**不可变性保证：**
- 面试过程中不允许修改阶段计划
- 阶段覆盖度可以更新，但阶段定义不变

## 9. 审计与调试

### 9.1 决策快照

`answer_evaluation.candidate_pool_snapshot_json` 必须包含：
- 决策时刻的候选池（包含预生成和追问候选）
- 每道候选题的得分、来源、考察点
- 最终选中的题目及选择原因
- 未选中题目的排除原因

### 9.2 决策原因

`answer_evaluation.next_action` 对应的 `decision_reason` 示例：
- `FOLLOW_UP`：回答质量良好（得分 75），追问深度 1/2，选择追问候选
- `SWITCH_TOPIC`：回答质量中等（得分 55），当前主题"并发"已覆盖，切换到"JVM"
- `NEXT_STAGE`：当前阶段题量 8/8，required_topics 全部覆盖，进入 PROJECT 阶段
- `FINISH`：总题量 20/20，各阶段覆盖充分，结束面试

### 9.3 日志规范

**必须记录：**
- 每次决策的输入：评估事实、候选池、阶段计划、覆盖度
- 每次决策的输出：`nextAction`、`nextStage`、`selectedNextQuestionId`、`decisionReason`
- 异常情况：候选池为空、Redis 缺失、FastAPI 超时、版本冲突

**禁止记录：**
- 用户完整回答文本（仅记录长度和关键词）
- 用户联系方式
- 完整 LLM prompt

## 10. 实施检查清单

实现面试流程时，必须确保：

- [ ] FastAPI 不返回 `nextAction`、`nextStage`、`selectedNextQuestion`
- [ ] `StagePolicyEngine` 独立决策，不依赖 FastAPI 建议
- [ ] 阶段计划包含 `min_questions`、`max_questions`、`required_topics`、`max_follow_up_depth`
- [ ] 阶段覆盖度实时更新，记录 `covered_topics`、`missing_topics`、`current_topic_follow_up_count`
- [ ] 预生成候选池在提问后后台生成，存储到 Redis
- [ ] 追问候选池在回答提交时同步生成，基于回答事实
- [ ] `request_id` 设置唯一索引，实现回答提交幂等
- [ ] `version` 乐观锁防止并发修改会话
- [ ] 决策快照持久化到 `candidate_pool_snapshot_json`
- [ ] Redis 缺失时可基于快照或 MySQL 重建候选池
- [ ] 异步任务通过 `taskId` 查询状态实现幂等
- [ ] 重试间隔递增：立即、30s、5min、30min
- [ ] 超过 `max_retry` 进入死信队列
- [ ] 页面刷新后可基于 MySQL 恢复当前题目和历史问答
- [ ] 降级规则覆盖：回答质量差、AI 超时、候选池为空

---

**文档版本：** 1.0  
**最后更新：** 2026-07-16  
**维护者：** SmartView 开发团队
