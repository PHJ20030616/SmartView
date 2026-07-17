# Task 1.1 Docker Compose 基础设施设计

## 1. 目标

在 `smartview-infra` 中提供可直接用于本地开发联调的 Docker Compose 基础设施，一次启动以下五个长期运行服务：

- MySQL
- Redis
- RabbitMQ
- MinIO
- Chroma

执行 `docker compose up -d --wait` 后，五个服务都必须通过健康检查。Spring Boot 和 FastAPI 后续可从 `.env` 中读取宿主机连接地址、端口及凭据。

## 2. 实现方案

采用单一 `docker-compose.yml`，不构建自定义镜像，也不增加运行后退出的一次性初始化容器。这样 `docker compose ps` 中只出现需要长期运行的基础设施服务，健康状态与验收标准保持一致。

所有服务加入统一的 Compose 网络，并使用命名卷保存数据。宿主机端口允许通过 `.env` 覆盖，但未配置时固定使用任务指定端口。

## 3. 镜像版本

使用精确版本标签，禁止使用 `latest`、仅主版本或仅次版本标签：

| 服务 | 镜像 |
| --- | --- |
| MySQL | `mysql:8.4.10` |
| Redis | `redis:8.8.0-alpine` |
| RabbitMQ | `rabbitmq:4.3.2-management` |
| MinIO | `minio/minio:RELEASE.2025-09-07T16-13-09Z` |
| Chroma | `chromadb/chroma:1.5.9` |

## 4. 服务与端口

| 服务 | 容器端口 | 默认宿主机端口 |
| --- | --- | --- |
| MySQL | `3306` | `3306` |
| Redis | `6379` | `6379` |
| RabbitMQ AMQP | `5672` | `5672` |
| RabbitMQ Management | `15672` | `15672` |
| MinIO API | `9000` | `9000` |
| MinIO Console | `9001` | `9001` |
| Chroma API | `8000` | `8001` |

Compose 内部服务之间使用服务名访问，例如 `mysql:3306` 和 `chroma:8000`；宿主机运行的 Spring Boot、FastAPI 和开发工具使用 `.env` 中的 `localhost` 地址及映射端口。

## 5. 数据持久化

定义以下命名卷：

- `mysql_data`
- `redis_data`
- `rabbitmq_data`
- `minio_data`
- `chroma_data`

Redis 启用 AOF 持久化。Chroma 显式启用持久化并关闭匿名遥测。MinIO 以单节点模式启动，数据目录挂载到命名卷。

## 6. 初始化目录

### MySQL

`smartview-infra/mysql/init/` 挂载到官方镜像的 `/docker-entrypoint-initdb.d/`。

初始化脚本负责：

- 校验数据库名只包含字母、数字和下划线，避免将不安全标识符拼入 SQL。
- 确认应用数据库使用 `utf8mb4` 字符集和 `utf8mb4_0900_ai_ci` 排序规则。
- 仅在 MySQL 数据卷首次初始化时执行；已有数据卷不会重复执行。

官方镜像仍负责创建数据库、应用用户和授权，初始化脚本不重复维护账号逻辑。

### MinIO

`smartview-infra/minio/` 保存 MinIO 本地开发说明和后续策略、存储桶初始化扩展入口。本任务不自动创建存储桶，避免引入执行后退出的辅助容器；MinIO API、Console 和根账号登录能力属于本任务验收范围。

## 7. 环境变量

`smartview-infra/.env.example` 至少包含：

- Compose 项目名
- 五个服务的宿主机地址和端口
- MySQL 数据库名、应用用户、应用密码、根密码及 JDBC URL
- Redis 密码和连接 URL
- RabbitMQ 用户、密码、虚拟主机、AMQP URL 和管理端 URL
- MinIO 根用户、根密码、API URL、Console URL
- Chroma API URL

Compose 对启动必需变量提供本地开发默认值，因此未创建 `.env` 时也可以启动。示例凭据仅用于本地开发，不用于生产环境。

仓库根 `.gitignore` 必须明确忽略实际 `.env`，同时允许提交 `.env.example`，防止本地凭据进入远程仓库。

## 8. 健康检查

- MySQL：使用 `mysqladmin ping`，并通过容器环境变量读取根密码。
- Redis：使用带密码的 `redis-cli ping`。
- RabbitMQ：使用 `rabbitmq-diagnostics -q ping`。
- MinIO：访问 `/minio/health/live`。
- Chroma：使用容器内 Python 请求 `/api/v2/heartbeat`。

所有健康检查包含启动宽限期、超时、间隔和重试次数，避免服务首次初始化时被过早判定失败。服务统一使用 `unless-stopped` 重启策略。

## 9. 验收与测试

实施完成后执行以下验证：

1. `docker compose config --quiet`：验证 Compose 语法和变量展开。
2. `docker compose pull`：确认所有精确镜像标签存在并可拉取。
3. `docker compose up -d --wait`：启动并等待五个服务健康。
4. `docker compose ps`：确认五个长期运行容器均为 `healthy`。
5. MySQL：执行 `SELECT 1` 和数据库字符集查询。
6. Redis：执行认证后的 `PING`。
7. RabbitMQ：执行诊断命令，并通过管理 API 验证账号和端口。
8. MinIO：验证 API 健康端点、Console 端口和根凭据。
9. Chroma：请求 v2 heartbeat 接口。

最终报告必须列出实际执行命令、通过数量、失败数量以及任何未覆盖项。

## 10. 非目标

- 不在本任务中启动 Spring Boot、FastAPI 或 React。
- 不修改跨服务 OpenAPI、MQ Schema 或生成代码。
- 不提供生产环境高可用、TLS、外部密钥管理或集群配置。
- 不自动创建业务表、RabbitMQ 队列、MinIO 存储桶或 Chroma Collection。
