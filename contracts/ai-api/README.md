# FastAPI AI API 契约

## 契约用途

本目录存放 Spring Boot 后端与 FastAPI AI 服务之间的 HTTP API 契约定义，采用 OpenAPI 3.0 规范。

## 文件说明

- `openapi.yaml` — AI API 契约主文件，定义所有 AI 能力接口

## 文件命名规范

- 主契约文件：`openapi.yaml`
- 如需拆分，按能力域命名：`openapi-{domain}.yaml`（例如：`openapi-resume.yaml`、`openapi-interview.yaml`）
- 公共定义：`components/schemas/`、`components/responses/`

## 代码生成命令

### Spring Boot AI Client 生成

```bash
# 在 smartview-backend 目录下执行
mvn clean compile

# 使用 openapi-generator-maven-plugin 生成 AiServiceClient
# 配置见 pom.xml 中的 openapi-generator-maven-plugin
```

### FastAPI 服务端验证

```bash
# 在 smartview-ai 目录下执行
# 使用 FastAPI 的 OpenAPI 自动生成功能验证
uvicorn app.main:app --reload
# 访问 http://localhost:8000/docs 查看生成的文档
```

## 契约变更流程

### 1. 修改契约

在 `openapi.yaml` 中添加或修改 AI 接口定义：

```yaml
paths:
  /ai/resume/parse:
    post:
      summary: 解析简历 PDF
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/ResumeParseRequest'
      responses:
        '200':
          description: 解析成功
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ResumeParseResponse'
```

### 2. 生成代码

```bash
# 生成 Spring Boot AI Client
cd smartview-backend
mvn clean compile

# FastAPI 自动加载契约（如使用 pydantic 模型）
cd smartview-ai
python -m app.main
```

### 3. 实现业务逻辑

- Spring Boot：使用生成的 `AiServiceClient` 调用 AI 服务
- FastAPI：实现 AI 能力逻辑，确保响应符合契约

### 4. 运行测试

```bash
# Spring Boot 集成测试
cd smartview-backend
mvn test -Dtest=AiServiceClientTest

# FastAPI 接口测试
cd smartview-ai
pytest tests/test_ai_api.py
```

### 5. 提交代码

- 契约文件变更与代码实现在同一个 PR 中提交
- PR 描述中说明 AI 接口变更内容和调用方影响

## 调用规范

### 强制要求

**Spring Boot 调用 FastAPI 必须通过统一的 `AiServiceClient`**

```java
// ✅ 正确做法
@Service
public class InterviewService {
    @Autowired
    private AiServiceClient aiServiceClient;
    
    public QuestionDTO generateQuestion(GenerateQuestionRequest request) {
        return aiServiceClient.generateQuestion(request);
    }
}

// ❌ 错误做法 - 禁止直接使用 RestTemplate
@Service
public class InterviewService {
    @Autowired
    private RestTemplate restTemplate;  // 禁止！
    
    public QuestionDTO generateQuestion(GenerateQuestionRequest request) {
        return restTemplate.postForObject(
            "http://fastapi:8000/ai/question/generate",
            request,
            QuestionDTO.class
        );
    }
}
```

### 同步调用 vs 异步调用

**同步接口（通过 AiServiceClient）：**
- 适用场景：需要实时响应的 AI 能力
- 调用方式：Spring Boot 通过 `AiServiceClient` 调用 FastAPI HTTP API
- 典型场景：
  - 实时生成面试问题
  - 实时评估回答
  - 生成候选问题池

**异步任务（通过 RabbitMQ）：**
- 适用场景：耗时较长的 AI 任务
- 调用方式：Spring Boot 投递任务到 MQ，FastAPI Worker 消费任务
- 典型场景：
  - 简历 PDF 解析（可能需要 OCR）
  - 画像分析
  - 报告生成

**选择原则：**
- 响应时间 < 10 秒：使用同步接口（AiServiceClient）
- 响应时间 > 10 秒 或不确定：使用异步任务（MQ）
- 用户需要立即看到结果：使用同步接口
- 可以后台处理：使用异步任务

### AiServiceClient 封装的能力

- 统一异常处理
- 重试机制（网络抖动、超时）
- 熔断降级（AI 服务不可用时的降级策略）
- 链路追踪（traceId 传递）
- 超时控制

## 注意事项

1. **禁止绕过 AiServiceClient** — Spring Boot 不得在业务代码中直接调用 FastAPI
2. **先改契约，再写代码** — AI 接口变更必须先修改 `openapi.yaml`
3. **异步任务优先使用 MQ** — 耗时 AI 任务（简历解析、报告生成）应通过 MQ 异步处理
4. **同步接口需要超时控制** — 实时问答等同步接口需设置合理超时时间
5. **AI 服务只返回评估事实** — 不在 AI 层做业务决策（如阶段切换、下一题选择）

## 接口分类

### 同步接口

适用于需要实时响应的场景：

- `/ai/question/generate` — 生成面试问题
- `/ai/answer/evaluate` — 评估回答
- `/ai/candidates/generate` — 生成候选问题池

### 异步接口（通过 MQ）

适用于耗时较长的任务，见 `contracts/mq/README.md`：

- 简历解析任务
- 画像分析任务
- 报告生成任务

## 常见问题

### Q: 为什么必须使用 AiServiceClient？

A: 统一封装重试、熔断、链路追踪等能力，避免在业务代码中散落 HTTP 调用逻辑。

### Q: AI 接口超时时间如何设置？

A: 在 AiServiceClient 中配置，建议同步接口 10-30 秒，异步任务通过 MQ 处理。

### Q: FastAPI 服务不可用时如何降级？

A: AiServiceClient 实现熔断降级逻辑，返回默认响应或抛出业务异常，由上层决定降级策略。
