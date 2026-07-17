# MQ 消息契约

本目录包含 SmartView 系统中 RabbitMQ 消息的 JSON Schema 定义。

## 文件说明

| 文件 | 消息类型 | 说明 |
|------|---------|------|
| `resume_parse_task.schema.json` | RESUME_PARSE_TASK | 简历解析任务 |
| `resume_parse_result.schema.json` | RESUME_PARSE_RESULT | 简历解析结果 |
| `resume_vectorize_task.schema.json` | RESUME_VECTORIZE_TASK | 简历向量化任务 |
| `resume_vectorize_result.schema.json` | RESUME_VECTORIZE_RESULT | 简历向量化结果 |
| `profile_analyze_task.schema.json` | PROFILE_ANALYZE_TASK | 画像分析任务 |
| `profile_analyze_result.schema.json` | PROFILE_ANALYZE_RESULT | 画像分析结果 |
| `report_generate_task.schema.json` | REPORT_GENERATE_TASK | 报告生成任务 |
| `report_generate_result.schema.json` | REPORT_GENERATE_RESULT | 报告生成结果 |

## 公共字段规范

所有 MQ 消息必须包含以下公共字段：

| 字段 | 类型 | 必需 | 说明 |
|-----|------|------|------|
| `taskId` | string (uuid) | 是 | 任务唯一标识 |
| `traceId` | string (uuid) | 是 | 链路追踪 ID |
| `messageType` | string (enum) | 是 | 消息类型枚举 |
| `schemaVersion` | string | 是 | Schema 版本号，当前为 1.0.0 |
| `retryCount` | integer | 是 | 当前重试次数 |
| `createdAt` | string (date-time) | 是 | 消息创建时间 |

## 重试策略

- **最大重试次数**：所有任务最多重试 3 次
- **retryCount 规则**：生产者投递时 `retryCount` 初始为 0，消费失败后递增
- **死信队列**：`retryCount` 达到 3 次后，消息进入死信队列 `smartview.dlx`
- **重试间隔**：建议使用指数退避策略（1s、2s、4s）

## 消息结果规范

所有 `*_result` 消息必须遵循以下规则：
- 当 `success=true` 时，`errorMessage` 字段应为空或不存在
- 当 `success=false` 时，`errorMessage` 字段为必需，包含明确的错误原因

## 消息类型登记

| messageType | 队列名称 | 说明 |
|------------|---------|------|
| RESUME_PARSE_TASK | smartview.resume.parse.task | 简历解析任务 |
| RESUME_PARSE_RESULT | smartview.resume.parse.result | 简历解析结果 |
| RESUME_VECTORIZE_TASK | smartview.resume.vectorize.task | 简历向量化任务 |
| RESUME_VECTORIZE_RESULT | smartview.resume.vectorize.result | 简历向量化结果 |
| PROFILE_ANALYZE_TASK | smartview.profile.analyze.task | 画像分析任务 |
| PROFILE_ANALYZE_RESULT | smartview.profile.analyze.result | 画像分析结果 |
| REPORT_GENERATE_TASK | smartview.report.generate.task | 报告生成任务 |
| REPORT_GENERATE_RESULT | smartview.report.generate.result | 报告生成结果 |

## 任务触发时序

- **简历解析任务**：用户上传 PDF 后由 Spring Boot 立即创建并投递
- **简历向量化任务**：用户确认简历后由 Spring Boot 创建并投递，FastAPI 消费后将简历切片写入 Chroma
- **画像分析任务**：用户选择面试方向后，Spring Boot 先校验简历向量已成功入库，再创建并投递
- **报告生成任务**：面试会话结束后由 Spring Boot 创建并投递

## 使用规范

### 契约变更流程

1. 修改对应的 JSON Schema 文件
2. 更新 `messageType` 登记表
3. 重新生成消息校验代码
4. 更新生产者和消费者代码

### 代码生成

**Spring Boot**：
```bash
# 生成消息 DTO 和校验器
mvn generate-sources
```

**FastAPI**：
```bash
# 生成 Pydantic 模型
python scripts/generate_mq_schemas.py
```

## 注意事项

- 所有未登记的 `messageType` 会被拒绝
- Schema 版本不兼容时会记录错误并进入死信队列
- 消息投递和消费必须校验 JSON Schema
