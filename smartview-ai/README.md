# SmartView AI 服务

FastAPI AI 服务基础工程，只对 Spring Boot 后端开放能力接口。React 前端不得直接调用本服务。

## 本地启动

```bash
cd smartview-ai
python -m venv venv
venv\Scripts\activate
python -m pip install -e ".[test,ocr]"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000

python -m app.workers.resume_worker
```

简历解析采用独立 RabbitMQ worker 消费 `smartview.resume.parse` 队列。启动 FastAPI
接口后，需要在另一个终端启动 worker：

```bash
cd smartview-ai
venv\Scripts\activate
python -m app.workers.resume_worker
```

worker 会将解析结果发布到 `resume.parse.result`，Spring Boot 负责消费结果并更新
简历解析状态。RabbitMQ 连接（主机、端口、账号、密码、虚拟主机）统一在仓库根的
`smartview-infra/.env` 配置，启动时由 `app/core/config.py` 自动注入；
smartview-ai/.env 只保留交换机与队列等拓扑名（`RABBITMQ_EXCHANGE`、`RABBITMQ_RESUME_*`）。

简历画像确认后，Spring Boot 会将画像 ID 和版本号投递到
`smartview.resume.vectorize`，需要单独启动向量入库 worker：

```bash
cd smartview-ai
venv\Scripts\activate
python -m app.workers.resume_vectorize_worker
```

向量 worker 从 MySQL 读取 `CONFIRMED` 画像的 `raw_text`、项目经历和技能描述，
按 `RESUME_VECTOR_CHUNK_SIZE` / `RESUME_VECTOR_CHUNK_OVERLAP` 切片后写入
Chroma 的 `resume_profile_chunks` collection。MySQL/RabbitMQ 等外部服务的账号密码
与 base URL 统一在仓库根 `smartview-infra/.env` 配置（唯一事实来源）；Chroma 本地
路径与切片参数等 Python 专属项在 `smartview-ai/.env` 配置，完整示例见 `.env.example`。
向量库只作为检索加速层，写入失败不会撤销 MySQL 中已经确认的画像；前端会轮询状态并允许重试。

生产环境必须安装 `.[ocr]`，否则扫描型 PDF 在文本层不可用时会返回可读的 OCR 依赖错误。
同时请在 `smartview-ai/.env` 中配置 `DEEPSEEK_API_KEY` 和 `RESUME_ALLOWED_ORIGINS`；
Spring Boot 调用本服务所需的 `AI_SERVICE_API_KEY` 在 `smartview-infra/.env` 配置。
`RESUME_ALLOWED_ORIGINS` 必须与 Spring Boot 的 `MINIO_ENDPOINT` 完整来源一致，
例如本地使用 `["http://localhost:9000"]`；生产环境必须改为实际的 MinIO/S3 对象存储
来源，避免白名单主机被用于访问其他内部服务。

启动后访问：

- 健康检查：`http://127.0.0.1:8000/api/v1/health`
- OpenAPI 文档：`http://127.0.0.1:8000/docs`

## 日志

服务日志同时输出到控制台和 `logs/` 目录（按进程分文件、自动轮转）：

- `logs/smartview-api.log`：FastAPI 接口日志，包含每个 HTTP 请求的「收到请求 / 请求处理完成」记录（trace_id、客户端、状态码、耗时）
- `logs/resume-worker.log`：简历解析 MQ worker 日志
- `logs/resume-vectorize-worker.log`：简历向量入库 MQ worker 日志

每条日志自动携带 `trace_id=` 字段，可直接与 Spring Boot 的 `X-Trace-Id` 关联排查链路。
日志行为可通过 `.env` 配置：`LOG_LEVEL`、`LOG_FILE_ENABLED`、`LOG_DIR`、
`LOG_FILE_MAX_BYTES`、`LOG_FILE_BACKUP_COUNT`（示例见 `.env.example`）。
为避免重复，应用已关闭 uvicorn 自带的访问日志，改用包含 trace_id 与耗时的自定义访问日志。


## 测试

```bash
cd smartview-ai
python -m pytest
```
