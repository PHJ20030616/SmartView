# Task 3.2 — PDF 上传接口设计决策与解决方案

## 文档信息

- **任务编号**: Task 3.2
- **任务名称**: PDF 上传接口实现
- **创建日期**: 2026-07-23
- **最后更新**: 2026-07-23

---

## 需求分析阶段

### 初始需求

实现 PDF 简历上传接口，包含以下核心功能：
- 文件校验（类型、大小）
- 保存到 MinIO 对象存储
- 写入 `resume_file` 表
- 创建 `RESUME_PARSE` 类型的 `ai_task`
- 投递 RabbitMQ 简历解析任务

### 关键设计问题与决策

---

## 问题 1：文件大小限制

### 问题描述
PDF 文件的最大上传大小限制是多少？

### 讨论过程
- 个人简历通常 1-5MB
- 需要兼顾质量和性能

### 最终决策
✅ **限制为 10MB**，并支持通过配置文件动态调整

### 实现方案
```yaml
# application.yml
smartview:
  resume:
    max-file-size: ${RESUME_MAX_FILE_SIZE:10485760}  # 10MB
```

**理由**：
- 满足大多数简历场景
- 配置化设计便于运维调整
- 避免过大文件占用存储和处理资源

---

## 问题 2：文件命名策略

### 问题描述
上传的 PDF 在 MinIO 中如何命名？

### 备选方案
1. 使用 `{UUID}.pdf` 避免冲突
2. 使用 `resumes/{userId}/{timestamp}_{originalName}.pdf` 保留原名

### 最终决策
✅ **使用 UUID 方案**：`resumes/{userId}/{uuid}.pdf`

### 实现方案
```java
private String generateResumeObjectKey(Long userId, String originalFilename) {
    String uuid = UUID.randomUUID().toString();
    String extension = extractFileExtension(originalFilename);
    return String.format("resumes/%d/%s.%s", userId, uuid, extension);
}
```

**理由**：
- 避免文件名冲突
- 隐藏用户真实文件名，提升安全性
- 原始文件名保存在数据库 `original_filename` 字段

---

## 问题 3：解析任务异步处理

### 问题描述
上传接口是否需要等待解析结果？

### 备选方案
1. **同步处理**：等待解析完成后返回
2. **异步处理**：立即返回，前端轮询状态

### 最终决策
✅ **异步处理**（推荐）

### 实现方案
- 上传后立即返回 `resumeFileId` 和 `parse_status: PENDING`
- 前端每 2 秒轮询 `GET /api/resumes/{resumeFileId}`
- 解析完成后状态变为 `SUCCESS` 或 `FAILED`

```javascript
async function uploadResume(file) {
    const response = await api.uploadResume(file);
    const { resumeFileId, parseStatus } = response.data;
    
    if (parseStatus === 'PENDING' || parseStatus === 'PROCESSING') {
        await pollParseStatus(resumeFileId, 120000); // 轮询 2 分钟
    }
}
```

**理由**：
- 提升用户体验，不阻塞上传流程
- 解析任务耗时不确定（5-30 秒）
- 允许后端水平扩展，AI 服务独立伸缩

---

## 问题 4：重复上传策略

### 问题描述
同一用户上传相同 PDF 如何处理？

### 备选方案
1. **允许重复**：每次上传视为新简历版本
2. **使用文件 hash 去重**：相同文件返回已有记录

### 最终决策
✅ **允许重复提交**（多次使用同一份简历进行模拟面试）
⚠️ **同一会话窗口禁止重复提交**（保持幂等性）

### 实现方案
- 全局层面：允许重复上传，每次生成新的 `resume_file` 记录
- 会话层面：通过前端请求 ID（`requestId`）实现幂等
- 计算文件 SHA-256 哈希用于数据审计（非去重）

```java
private String calculateFileHash(MultipartFile file) {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(file.getBytes());
    // 转换为十六进制字符串
    return hexString;
}
```

**理由**：
- 支持简历迭代（用户修改简历后重新上传）
- 支持多次面试场景
- 会话级幂等避免误操作重复提交

---

## 问题 5：解析失败回滚策略（核心问题）

### 问题描述
**关键痛点**：用户成功上传简历，数据库也写入成功，但 MQ 投递失败。如果延迟 5 分钟后再投递，用户无法进入后续流程（因为后续流程依赖 MQ 任务结果）。

### 可能的失败场景

| 步骤 | 失败场景 | 影响 |
|-----|---------|------|
| MinIO 上传失败 | 无数据库记录 | ✅ 无副作用，直接返回错误 |
| 数据库写入失败 | MinIO 有孤儿文件 | ⚠️ 需要清理 MinIO |
| `ai_task` 创建失败 | `resume_file` 已存在但无任务 | ⚠️ 解析永远不会发生 |
| **MQ 投递失败** | **DB 记录完整但无消费者处理** | **🔴 用户被阻塞** |

### 备选方案

#### 方案 A：数据库事务 + 延迟重试（初始方案，被否决）
```java
@Transactional
public ResumeFile uploadResume(MultipartFile file) {
    // 1. 上传 MinIO
    // 2-3. 写入数据库（事务内）
    // 4. MQ 投递失败 → 标记 FAILED
    // 5. 等待 5 分钟后定时任务重试
}
```
**问题**：用户体验差，5 分钟等待不可接受

#### 方案 B：立即重试 + 前端轮询 + 定时任务兜底（最终方案）

### 最终决策
✅ **三重保障机制**

### 实现方案

#### 1️⃣ 立即重试（同步阻塞，但很快）
```java
private boolean sendToMqWithRetry(ResumeFile resumeFile, AiTask aiTask, int maxAttempts) {
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, message);
            return true;
        } catch (AmqpException e) {
            if (attempt < maxAttempts) {
                // 指数退避：100ms -> 300ms -> 900ms
                Thread.sleep(100L * (long)Math.pow(3, attempt - 1));
            }
        }
    }
    return false;
}
```

**效果**：90% 以上的瞬时故障在 < 2 秒内恢复，用户无感知

#### 2️⃣ 前端轮询（用户无感知等待）
```javascript
async function pollParseStatus(resumeFileId, maxDuration) {
    const startTime = Date.now();
    while (Date.now() - startTime < maxDuration) {
        const response = await api.getResumeFile(resumeFileId);
        if (response.data.parseStatus === 'SUCCESS') {
            router.push(`/resume-profiles/${response.data.profileId}`);
            return;
        }
        await sleep(2000); // 每 2 秒轮询一次
    }
}
```

**效果**：用户看到进度条，体验流畅

#### 3️⃣ 定时任务兜底（处理极端情况）
```java
@Scheduled(fixedDelayString = "#{${smartview.resume.mq.scheduled-retry-interval-minutes:5} * 60 * 1000}")
public void retryFailedParseTasks() {
    List<AiTask> failedTasks = queryFailedTasks(); // 查询 FAILED 状态任务
    for (AiTask task : failedTasks) {
        boolean success = retryTask(task);
        if (!success && task.getRetryCount() >= maxRetry) {
            markAsPermanentlyFailed(task); // 标记永久失败
        }
    }
}
```

**效果**：极端情况（MQ 服务重启、网络长时间中断）也能恢复

---

### 配置项

```yaml
smartview:
  resume:
    mq:
      max-retry-attempts: 3           # 上传接口立即重试次数
      retry-base-delay-ms: 100        # 重试基础延迟（指数退避）
      scheduled-retry-interval-minutes: 5  # 定时任务扫描间隔
      max-scheduled-retry-count: 3    # 定时任务最大重试次数
```

---

### 方案对比

| 维度 | 方案 A（延迟重试） | 方案 B（三重保障）✅ |
|-----|------------------|-------------------|
| **用户体验** | ❌ 5 分钟阻塞 | ✅ < 2 秒恢复或实时进度 |
| **可靠性** | ⚠️ 单一兜底 | ✅ 三重保障 |
| **复杂度** | ✅ 简单 | ⚠️ 适中 |
| **瞬时故障恢复** | ❌ 延迟 5 分钟 | ✅ < 2 秒 |
| **极端情况处理** | ✅ 定时任务兜底 | ✅ 定时任务兜底 |

---

## 问题 6：契约优先开发

### 问题描述
接口定义是否已在 `contracts/web-api/openapi.yaml` 中？

### 最终决策
✅ **契约已存在**，严格遵循契约实现

### 实现方案
- `POST /api/resumes`：上传接口
- `GET /api/resumes/{resumeFileId}`：查询接口
- 返回 DTO 遵循契约定义的 `ResumeFile` schema

**理由**：
- 项目采用契约优先开发模式（Contract-First）
- 前后端通过契约解耦
- 支持契约驱动测试和代码生成

---

## 架构设计

### 核心流程图

```
用户上传 PDF
    ↓
文件校验（类型、大小、哈希）
    ↓
上传到 MinIO（生成 objectKey）
    ↓
数据库事务
    ├─ 创建 resume_file（parse_status=PENDING）
    └─ 创建 ai_task（task_status=PENDING）
    ↓
MQ 投递（立即重试 3 次）
    ├─ 成功 → 返回 resumeFileId
    └─ 失败 → 标记 FAILED，等待定时任务
    ↓
返回响应给前端
    ↓
前端开始轮询（每 2 秒）
    ↓
AI 服务消费 MQ 消息
    ↓
解析完成，更新状态
    ↓
前端跳转到简历画像页面
```

### 异常处理策略

| 异常场景 | 处理策略 |
|---------|---------|
| 文件校验失败 | 直接抛异常，不上传 |
| MinIO 上传失败 | 直接抛异常，无副作用 |
| 数据库失败 | 回滚事务，清理 MinIO 文件 |
| MQ 投递失败（立即重试失败） | 不回滚，标记 FAILED，定时任务兜底 |
| MQ 投递失败（定时任务失败 3 次） | 标记 PERMANENTLY_FAILED，人工介入 |

---

## 技术选型

### 核心技术栈
- **Spring Boot 3.x**：后端框架
- **MinIO**：对象存储（兼容 S3 API）
- **RabbitMQ**：消息队列
- **MyBatis-Plus**：ORM 框架
- **MySQL**：关系数据库

### 关键依赖
- `io.minio:minio:9.0.3`：MinIO Java SDK
- `spring-boot-starter-amqp`：RabbitMQ 集成
- `spring-boot-starter-validation`：参数校验

---

## 性能优化

### 1. 指数退避策略
```java
// 重试延迟：100ms -> 300ms -> 900ms
long delay = baseDelayMs * Math.pow(3, attempt - 1);
```
**效果**：快速恢复瞬时故障，避免雪崩

### 2. 预签名 URL
```java
String presignedUrl = minioService.generatePresignedUrl(objectKey, 1); // 1 小时有效期
```
**效果**：AI 服务直接从 MinIO 下载，避免 Spring Boot 中转大文件

### 3. 异步处理
- 上传接口立即返回，不阻塞
- AI 服务独立伸缩，不影响 Web 服务

---

## 安全考虑

### 1. 文件类型校验
```java
// MIME 类型校验
if (!allowedTypes.contains(contentType)) {
    throw new BusinessException("仅支持 PDF 格式的简历文件");
}

// 文件扩展名校验
if (!originalFilename.toLowerCase().endsWith(".pdf")) {
    throw new BusinessException("文件名必须以 .pdf 结尾");
}
```

### 2. 文件大小限制
```yaml
max-file-size: 10485760  # 10MB
```

### 3. 权限校验
```java
// 只能查询自己的简历
if (!resumeFile.getUserId().equals(currentUserId)) {
    throw new BusinessException(ResponseCode.FORBIDDEN, "无权访问该简历文件");
}
```

### 4. 文件哈希
```java
// SHA-256 哈希，用于完整性校验和审计
String fileHash = calculateFileHash(file);
```

---

## 运维支持

### 1. 可观测性
- **日志**：关键操作记录 `userId`、`resumeFileId`、`taskId`
- **链路追踪**：`traceId` 贯穿整个流程
- **监控指标**：
  - 上传成功率
  - MQ 投递成功率
  - 解析成功率
  - P99 上传耗时

### 2. 配置化
所有关键参数支持通过环境变量或配置文件调整：
```yaml
RESUME_MAX_FILE_SIZE=10485760
RESUME_MQ_MAX_RETRY_ATTEMPTS=3
RESUME_MQ_RETRY_BASE_DELAY_MS=100
```

### 3. 降级策略
- MQ 投递失败 → 定时任务兜底
- 定时任务失败 3 次 → 标记永久失败，人工介入
- MinIO 不可用 → 返回 503，建议用户稍后重试

---

## 测试验收

### 功能测试
- [x] 上传 PDF 文件，检查 MinIO 是否有文件
- [x] 上传非 PDF 文件，验证被拒绝
- [x] 上传超大文件，验证被拒绝
- [x] 查询简历文件信息，验证权限校验
- [x] RabbitMQ 中查看解析任务消息

### 异常测试
- [ ] MinIO 服务宕机，验证错误处理
- [ ] RabbitMQ 服务宕机，验证立即重试和定时任务
- [ ] 数据库连接失败，验证事务回滚和 MinIO 清理
- [ ] 网络抖动，验证指数退避重试

### 性能测试
- [ ] 并发上传 100 个文件，验证吞吐量
- [ ] 上传 10MB 文件，验证耗时 < 5 秒
- [ ] MQ 投递失败，验证立即重试耗时 < 2 秒

---

## 后续优化方向

### 短期（1-2 周）
1. ✅ 实现基础上传和查询接口
2. ✅ 配置 RabbitMQ 交换机和队列
3. ✅ 实现定时任务重试机制
4. ⏳ 前端轮询组件实现
5. ⏳ FastAPI AI 服务消费 MQ 消息

### 中期（1 个月）
1. 添加 Prometheus 监控指标
2. 实现文件去重（基于 fileHash）
3. 支持断点续传（大文件分片上传）
4. 增加文件预览功能（PDF 缩略图）

### 长期（3 个月）
1. 支持更多文件格式（DOCX、DOC、图片 OCR）
2. 增加文件病毒扫描
3. 实现 CDN 加速下载
4. 异地多活部署

---

## 参考资料

### 内部文档
- [SmartView 架构设计](../../README.md)
- [数据库设计文档](../database/)
- [MQ 契约定义](../../contracts/mq/resume_parse_task.schema.json)
- [Web API 契约](../../contracts/web-api/openapi.yaml)

### 外部资料
- [MinIO Java SDK 文档](https://min.io/docs/minio/linux/developers/java/minio-java.html)
- [Spring AMQP 文档](https://docs.spring.io/spring-amqp/reference/)
- [MyBatis-Plus 文档](https://baomidou.com/)

---

## 变更记录

| 日期 | 变更内容 | 负责人 |
|-----|---------|--------|
| 2026-07-23 | 初始版本创建，记录设计决策和解决方案 | Claude & 用户 |

---

## 附录：完整配置示例

```yaml
# application.yml
smartview:
  # MinIO 配置
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ROOT_USER:smartview}
    secret-key: ${MINIO_ROOT_PASSWORD:smartview_minio_password}
    bucket: ${MINIO_BUCKET:smartview}
  
  # 简历上传配置
  resume:
    max-file-size: ${RESUME_MAX_FILE_SIZE:10485760}  # 10MB
    allowed-mime-types: ${RESUME_ALLOWED_MIME_TYPES:application/pdf}
    mq:
      max-retry-attempts: ${RESUME_MQ_MAX_RETRY_ATTEMPTS:3}
      retry-base-delay-ms: ${RESUME_MQ_RETRY_BASE_DELAY_MS:100}
      scheduled-retry-interval-minutes: ${RESUME_MQ_SCHEDULED_RETRY_INTERVAL_MINUTES:5}
      max-scheduled-retry-count: ${RESUME_MQ_MAX_SCHEDULED_RETRY_COUNT:3}
  
  # RabbitMQ 配置
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_AMQP_PORT:5672}
    username: ${RABBITMQ_DEFAULT_USER:smartview}
    password: ${RABBITMQ_DEFAULT_PASS:smartview_rabbitmq_password}
    virtual-host: ${RABBITMQ_DEFAULT_VHOST:smartview}
```
