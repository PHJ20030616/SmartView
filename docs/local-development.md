# 本地开发指南

启动命令见仓库根 `README.md` 的「本地开发入口」；本文只讲**环境变量**与**常见问题**。

## 环境要求

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | `smartview-server` 要求 |
| Maven | 3.6.3+ | 仓库**不带** Maven Wrapper，使用系统 `mvn`；用 `mvn -v` 确认版本 |
| Python | 3.13 | 虚拟环境已置于 `smartview-ai/venv` |
| Node.js | 20+ | `smartview-web` |
| Docker | 近期版本 | 运行 `smartview-infra` 中的基础设施 |

## 环境变量只有一份

所有环境变量集中在 **`smartview-infra/.env`**，三方共用：

| 消费方 | 读取方式 |
| --- | --- |
| `smartview-infra` 的 docker-compose | Compose 自动读取同目录 `.env` |
| `smartview-ai`（Python） | `app/core/config.py` 的 `load_dotenv` 显式加载 |
| `smartview-server`（Java） | `application.yml` 的 `spring.config.import` 导入 |

**要改数据库密码、MinIO 凭据或 JWT 密钥，只改 `smartview-infra/.env` 这一处。**

### 仓库根目录的 `.env` 已废弃，请勿修改

仓库根还存在一份早期留下的 `.env`。它已被 `.gitignore` 忽略、不在任何脚本或配置的引用链路上，
**也不在本项目的配置读取链路上**（Java 侧导入的是 `smartview-infra/.env`）。
内容上它比 `smartview-infra/.env` 少四个键：`JWT_SECRET`、`AI_SERVICE_API_KEY`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`。

它目前不会造成故障：经逐键核对，两者在 docker-compose 实际使用的全部键（MySQL / Redis /
RabbitMQ / MinIO / Chroma 的连接参数）上取值一致。但这只是当下的巧合——
只要按本文档只修改 `smartview-infra/.env`，两份文件迟早会漂移，而任何从仓库根执行
`docker compose -f smartview-infra/docker-compose.yml ...` 的操作都可能读到根目录那份陈旧值。

处置方式：**不要改它**。想彻底消除误用风险可以直接删除该文件（它不在版本管理内，删除后不可恢复）。

### 为什么 Java 侧要显式声明 `spring.config.import`

Spring Boot **不认** `.env` 格式——那是 Docker Compose / shell 的约定，不是 Java 生态的。
缺少这条声明时，`application.yml` 里所有 `${VAR:默认值}` 会**静默**回落到默认值：
服务照常启动、功能照常可用，只是在改了 `.env` 之后"什么都没变"。这类问题极难排查，
因此有 `SharedInfraEnvImportTest` 专门守住这条声明。

Docker / CI 场景可用 `SPRING_CONFIG_IMPORT` 环境变量整体覆盖导入路径。注意本项目的 CI
走的不是这条通道：`.github/workflows/ci.yml` 的后端 job 使用 `application-test.yml`
并在 workflow 中注入所需环境变量。

### 打包运行（`java -jar`）

上面两个导入路径是**按进程当前工作目录**解析的，因此 `java -jar` 不受它们保护：
只有在仓库根或 `smartview-server/` 目录下执行才会命中，在其它目录启动请显式指定：

```bash
java -jar smartview-server/target/smartview-server-0.1.0-SNAPSHOT.jar \
  --spring.config.import=optional:file:/绝对路径/smartview-infra/.env[.properties]
```

漏配的后果是静默的：`.env` 没被读到，所有 `${VAR:默认值}` 走回退值，服务照常启动，
只是你改的配置不生效。因此打包部署后请确认启动日志里没有 `Skipped config file` 提示，
或用上面「常见问题」里的端口探针法确认通道已通。

### JWT 密钥

`JWT_SECRET` 至少 32 字符，且**没有回退默认值**：未配置时后端在启动阶段直接失败，
而不是带着一个可预测的密钥运行。生成方式：

```bash
openssl rand -base64 48
```

## 常见问题

**改了 `.env`，但服务行为没变**

确认三点：改的是 `smartview-infra/.env`（不是仓库根目录那份已废弃的副本）；服务已重启；
Java 侧启动日志中没有 `Skipped config file` 之类的提示。

**后端启动报 `smartview.jwt.secret` 校验失败**

`JWT_SECRET` 未配置或不足 32 字符。按上文生成后写入 `smartview-infra/.env`。

**Flyway 迁移失败**

确认 MySQL 容器已就绪（`docker compose ps`），且 `MYSQL_JDBC_URL` 中的库名与 `MYSQL_DATABASE` 一致。

**端口被占用**

`SERVER_PORT` 未在 `.env` 中定义时默认 8080。需要改端口时在 `smartview-infra/.env` 中新增
`SERVER_PORT=目标端口`，重启后端后用启动日志中的 `Tomcat started on port ...` 确认已生效。

**改完 `.env` 想快速确认通道是否真的通了**

在 `.env` 里临时加一行无副作用的键（例如 `SERVER_PORT=18080`）并重启后端：端口随之改变，
说明通道正常；若端口没变，说明没读到这份 `.env`。验证完记得删掉该行。
