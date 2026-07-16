# 消息队列契约

## 契约用途

本目录存放 Spring Boot 与 FastAPI 之间通过 RabbitMQ 传递的异步消息契约，采用 JSON Schema 规范。

## 文件说明

每个消息类型对应一个独立的 JSON Schema 文件：

- `resume_parse_task.schema.json` — 简历解析任务消息
- `resume_parse_result.schema.json` — 简历解析结果消息
- `profile_analyze_task.schema.json` — 画像分析任务消息
- `profile_analyze_result.schema.json` — 画像分析结果消息
- `report_generate_task.schema.json` — 报告生成任务消息
- `report_generate_result.schema.json` — 报告生成结果消息

## 文件命名规范

- 任务消息：`{task_name}_task.schema.json`
- 结果消息：`{task_name}_result.schema.json`
- 使用小写字母和下划线分隔

## 消息结构规范

所有消息必须包含以下公共字段：

```json
{
  "taskId": "string (UUID)",
  "traceId": "string (UUID)",
  "messageType": "string (消息类型标识)",
  "schemaVersion": "string (契约版本号，如 1.0.0)",
  "retryCount": "integer (当前重试次数)",
  "createdAt": "string (ISO 8601 时间戳)",
  "payload": {
    // 具体业务数据
  }
}
```

### 字段说明

- **taskId** — 任务唯一标识，用于幂等性保障
- **traceId** — 链路追踪标识，关联整个业务流程
- **messageType** — 消息类型，用于路由和校验（如 `RESUME_PARSE_TASK`）
- **schemaVersion** — 契约版本号，用于消息版本管理
- **retryCount** — 重试次数，用于重试策略控制
- **createdAt** — 消息创建时间，用于超时和过期判断
- **payload** — 业务数据载荷，具体结构见各消息 Schema

## 代码生成与校验

### Spring Boot 消息生产者

```java
// 使用 jsonschema2pojo-maven-plugin 生成 Java DTO
// 配置见 pom.xml

// 投递前校验
@Service
public class MqMessageService {
    @Autowired
    private JsonSchemaValidator schemaValidator;
    
    public void publishResumeParseTask(ResumeParseTask task) {
        // 校验消息符合 Schema
        schemaValidator.validate(task, "resume_parse_task.schema.json");
        rabbitTemplate.convertAndSend("resume.parse.queue", task);
    }
}
```

### FastAPI 消息消费者

```python
# 使用 pydantic 模型验证消息
from pydantic import BaseModel, Field
from datetime import datetime

class ResumeParseTask(BaseModel):
    task_id: str = Field(..., description="任务 ID")
    trace_id: str = Field(..., description="链路追踪 ID")
    message_type: str = Field(..., description="消息类型")
    schema_version: str = Field(..., description="契约版本")
    retry_count: int = Field(default=0, description="重试次数")
    created_at: datetime = Field(..., description="创建时间")
    payload: dict = Field(..., description="业务数据")

# 消费时自动校验
async def consume_resume_parse_task(message: dict):
    task = ResumeParseTask(**message)  # 自动校验
    await process_resume_parse(task)
```

## 契约变更流程

### 1. 修改 JSON Schema

在对应的 `.schema.json` 文件中修改消息结构：

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ResumeParseTask",
  "type": "object",
  "required": ["taskId", "traceId", "messageType", "schemaVersion", "createdAt", "payload"],
  "properties": {
    "taskId": { "type": "string", "format": "uuid" },
    "traceId": { "type": "string", "format": "uuid" },
    "messageType": { "type": "string", "const": "RESUME_PARSE_TASK" },
    "schemaVersion": { "type": "string", "pattern": "^\\d+\\.\\d+\\.\\d+$" },
    "retryCount": { "type": "integer", "minimum": 0, "default": 0 },
    "createdAt": { "type": "string", "format": "date-time" },
    "payload": {
      "type": "object",
      "required": ["userId", "resumeFileId", "objectKey"],
      "properties": {
        "userId": { "type": "integer", "format": "int64" },
        "resumeFileId": { "type": "integer", "format": "int64" },
        "objectKey": { "type": "string" }
      }
    }
  }
}
```

### 2. 更新契约版本

- 破坏性变更：升级主版本号（1.0.0 → 2.0.0）
- 新增字段：升级次版本号（1.0.0 → 1.1.0）
- Bug 修复：升级修订号（1.0.0 → 1.0.1）

### 3. 生成代码

```bash
# Spring Boot 生成 DTO
cd smartview-backend
mvn clean compile

# FastAPI 更新 Pydantic 模型
cd smartview-ai
# 手动同步或使用代码生成工具
```

### 4. 兼容性测试

- 验证新旧版本消息是否兼容
- 验证消费者能否正确处理新消息
- 验证错误消息是否被正确拒绝

### 5. 提交代码

- Schema 变更、代码生成、业务实现在同一个 PR 中提交
- PR 描述中说明消息结构变更和影响范围

## 幂等性保障

所有消息处理必须保证幂等性：

### 任务表设计

```sql
CREATE TABLE ai_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL UNIQUE,  -- 消息中的 taskId
    task_type VARCHAR(32) NOT NULL,
    task_status VARCHAR(16) NOT NULL,
    trace_id VARCHAR(64),
    retry_count INT DEFAULT 0,
    max_retry INT DEFAULT 3,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_task_id (task_id),
    INDEX idx_trace_id (trace_id)
);
```

### 幂等性检查

```java
// Spring Boot 消费者
@RabbitListener(queues = "resume.parse.queue")
public void handleResumeParseTask(ResumeParseTask task) {
    // 幂等性检查
    if (aiTaskRepository.existsByTaskId(task.getTaskId())) {
        log.info("任务已处理，跳过: {}", task.getTaskId());
        return;  // 幂等，直接返回
    }
    
    // 处理任务
    processResumeParseTask(task);
}
```

```python
# FastAPI Worker
async def handle_resume_parse_task(task: ResumeParseTask):
    """
    FastAPI Worker 的幂等性保障策略：
    
    方案1（推荐）：依赖消息队列的去重机制
    - 使用 taskId 作为消息的去重键（Message Deduplication ID）
    - RabbitMQ 或其他 MQ 自动过滤重复消息
    
    方案2：FastAPI 维护自己的任务处理记录表
    - 仅记录 taskId 和处理状态，不存储业务数据
    - 业务数据由 Spring Boot 消费结果时写入 MySQL
    
    注意：FastAPI Worker 不应该回调 Spring Boot HTTP API 查询任务状态，
    这会破坏架构边界和服务独立性。
    """
    logger.info(f"开始处理任务: {task.task_id}")
    
    # 方案1：直接处理任务，幂等性由 MQ 去重机制保障
    await process_resume_parse(task)
    
    # 方案2（可选）：如果 MQ 不支持去重，可以在 FastAPI 侧维护处理记录
    # if await is_task_processed_locally(task.task_id):
    #     logger.info(f"任务已处理，跳过: {task.task_id}")
    #     return
    # await mark_task_as_processed_locally(task.task_id)
    # await process_resume_parse(task)
```

## 重试策略

### 重试配置

- **最大重试次数**：3 次
- **重试间隔**：指数退避（1s、2s、4s）
- **死信队列**：超过最大重试次数后进入死信队列

### 重试实现

```java
// Spring Boot 配置
@Configuration
public class RabbitMqConfig {
    @Bean
    public Queue resumeParseQueue() {
        return QueueBuilder.durable("resume.parse.queue")
            .withArgument("x-dead-letter-exchange", "dlx.exchange")
            .withArgument("x-dead-letter-routing-key", "dlx.resume.parse")
            .withArgument("x-message-ttl", 60000)  // 消息 TTL
            .build();
    }
}
```

## 注意事项

1. **消息必须符合 JSON Schema** — 投递和消费前都需要校验
2. **幂等性保障** — 通过 `taskId` 去重，避免重复执行
3. **重试策略** — 失败后按指数退避重试，超过次数进入死信队列
4. **版本管理** — 使用 `schemaVersion` 字段标识消息版本
5. **链路追踪** — 通过 `traceId` 关联整个业务流程
6. **超时控制** — 设置消息 TTL，避免过期消息堆积

## 队列设计

### 任务队列

- `resume.parse.queue` — 简历解析任务
- `profile.analyze.queue` — 画像分析任务
- `report.generate.queue` — 报告生成任务

### 结果队列

- `resume.parse.result.queue` — 简历解析结果
- `profile.analyze.result.queue` — 画像分析结果
- `report.generate.result.queue` — 报告生成结果

### 死信队列

- `dlx.resume.parse.queue` — 简历解析失败消息
- `dlx.profile.analyze.queue` — 画像分析失败消息
- `dlx.report.generate.queue` — 报告生成失败消息

## 常见问题

### Q: 如何保证消息不丢失？

A: 
- 生产者：使用持久化消息和 Publisher Confirm
- 队列：设置 durable=true
- 消费者：手动 ACK，处理成功后才确认

### Q: 如何处理消费失败？

A: 根据 `retryCount` 判断，未超过 `maxRetry` 则重新投递，否则进入死信队列。

### Q: 消息版本升级如何保证兼容性？

A: 
- 新增字段设置为可选（非 required）
- 使用 `schemaVersion` 字段区分不同版本
- 消费者支持多版本消息处理
