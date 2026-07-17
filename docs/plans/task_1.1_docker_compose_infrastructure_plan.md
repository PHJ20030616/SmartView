# Task 1.1 Docker Compose 基础设施 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `smartview-infra` 中提供 MySQL、Redis、RabbitMQ、MinIO、Chroma 的本地开发 Docker Compose 基础设施。

**Architecture:** 使用单个 `docker-compose.yml` 管理五个长期运行服务，所有服务加入同一网络并使用命名卷持久化数据。`.env.example` 定义 Spring Boot、FastAPI 后续读取所需的连接变量；Compose 自身提供本地开发默认值，未创建 `.env` 也可启动。

**Tech Stack:** Docker Compose v5、MySQL 8.4、Redis 8、RabbitMQ 4 Management、MinIO、Chroma。

---

## File Structure

- Create: `smartview-infra/docker-compose.yml`
  - 负责定义五个基础设施服务、固定宿主机端口、命名卷、统一网络和健康检查。服务不设置固定 `container_name`，避免多个工作区或旧容器并行存在时发生名称冲突。
- Create: `smartview-infra/.env.example`
  - 负责列出本地开发和后续应用服务读取所需的环境变量。
- Create: `smartview-infra/mysql/init/01-ensure-database.sh`
  - 负责在 MySQL 首次初始化时读取环境变量、校验数据库名，并确保数据库字符集为 `utf8mb4`。
- Create: `smartview-infra/mysql/init/README.md`
  - 说明 MySQL 初始化脚本的执行时机和边界。
- Create: `smartview-infra/minio/README.md`
  - 说明 MinIO 本地登录信息、API/Console 地址和后续存储桶初始化扩展方式。
- Modify: `.gitignore`
  - 明确保留 `.env.example`，防止未来通配规则误忽略示例文件。
- Create: `.gitattributes`
  - 约束 MySQL 初始化脚本使用 LF 换行，避免 Windows 换行导致 Linux 容器执行异常。

## Implementation Tasks

### Task 1: Create infrastructure files

**Files:**
- Create: `smartview-infra/docker-compose.yml`
- Create: `smartview-infra/.env.example`
- Create: `smartview-infra/mysql/init/01-ensure-database.sh`
- Create: `smartview-infra/mysql/init/README.md`
- Create: `smartview-infra/minio/README.md`
- Modify: `.gitignore`
- Create: `.gitattributes`

- [ ] **Step 1: Create `smartview-infra/docker-compose.yml`**

Use the exact service set below. Health checks must stay inside the service containers so `docker compose up -d --wait` can evaluate them without extra host tooling.

```yaml
name: ${COMPOSE_PROJECT_NAME:-smartview-infra}

services:
  mysql:
    image: mysql:8.4.10
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-smartview_root_password}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-smartview}
      MYSQL_USER: ${MYSQL_USER:-smartview}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:-smartview_password}
      TZ: ${TZ:-Asia/Shanghai}
    ports:
      - "${MYSQL_PORT:-3306}:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./mysql/init:/docker-entrypoint-initdb.d:ro
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p$${MYSQL_ROOT_PASSWORD} --silent"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 40s
    networks:
      - smartview-infra

  redis:
    image: redis:8.8.0-alpine
    restart: unless-stopped
    environment:
      REDIS_PASSWORD: ${REDIS_PASSWORD:-smartview_redis_password}
    command:
      - redis-server
      - --appendonly
      - "yes"
      - --requirepass
      - ${REDIS_PASSWORD:-smartview_redis_password}
    ports:
      - "${REDIS_PORT:-6379}:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$${REDIS_PASSWORD:-smartview_redis_password}\" ping | grep PONG"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 10s
    networks:
      - smartview-infra

  rabbitmq:
    image: rabbitmq:4.3.2-management
    restart: unless-stopped
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER:-smartview}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS:-smartview_rabbitmq_password}
      RABBITMQ_DEFAULT_VHOST: ${RABBITMQ_DEFAULT_VHOST:-smartview}
    ports:
      - "${RABBITMQ_AMQP_PORT:-5672}:5672"
      - "${RABBITMQ_MANAGEMENT_PORT:-15672}:15672"
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 30s
    networks:
      - smartview-infra

  minio:
    image: minio/minio:RELEASE.2025-09-07T16-13-09Z
    restart: unless-stopped
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-smartview}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-smartview_minio_password}
      TZ: ${TZ:-Asia/Shanghai}
    ports:
      - "${MINIO_API_PORT:-9000}:9000"
      - "${MINIO_CONSOLE_PORT:-9001}:9001"
    volumes:
      - minio_data:/data
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://127.0.0.1:9000/minio/health/live >/dev/null"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 20s
    networks:
      - smartview-infra

  chroma:
    image: chromadb/chroma:1.5.9
    restart: unless-stopped
    environment:
      IS_PERSISTENT: "TRUE"
      PERSIST_DIRECTORY: /chroma/chroma
      ANONYMIZED_TELEMETRY: "FALSE"
    ports:
      - "${CHROMA_PORT:-8001}:8000"
    volumes:
      - chroma_data:/chroma/chroma
    healthcheck:
      test:
        - CMD-SHELL
        - >-
          bash -ec 'exec 3<>/dev/tcp/127.0.0.1/8000;
          printf "GET /api/v2/heartbeat HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n" >&3;
          head -n 1 <&3 | grep "200"'
      interval: 10s
      timeout: 5s
      retries: 18
      start_period: 30s
    networks:
      - smartview-infra

volumes:
  mysql_data:
  redis_data:
  rabbitmq_data:
  minio_data:
  chroma_data:

networks:
  smartview-infra:
    driver: bridge
```

- [ ] **Step 2: Create `smartview-infra/.env.example`**

Use Chinese comments for developer-facing guidance and provide local-only sample secrets.

```dotenv
# SmartView 本地基础设施配置示例
# 使用方式：复制为 .env 后按需修改；.env 不应提交到仓库。

COMPOSE_PROJECT_NAME=smartview-infra
TZ=Asia/Shanghai

MYSQL_HOST=localhost
MYSQL_PORT=3306
MYSQL_DATABASE=smartview
MYSQL_USER=smartview
MYSQL_PASSWORD=smartview_password
MYSQL_ROOT_PASSWORD=smartview_root_password
MYSQL_JDBC_URL=jdbc:mysql://localhost:3306/smartview?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true

REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=smartview_redis_password
REDIS_URL=redis://:smartview_redis_password@localhost:6379/0

RABBITMQ_HOST=localhost
RABBITMQ_AMQP_PORT=5672
RABBITMQ_MANAGEMENT_PORT=15672
RABBITMQ_DEFAULT_USER=smartview
RABBITMQ_DEFAULT_PASS=smartview_rabbitmq_password
RABBITMQ_DEFAULT_VHOST=smartview
RABBITMQ_AMQP_URL=amqp://smartview:smartview_rabbitmq_password@localhost:5672/smartview
RABBITMQ_MANAGEMENT_URL=http://localhost:15672

MINIO_HOST=localhost
MINIO_API_PORT=9000
MINIO_CONSOLE_PORT=9001
MINIO_ROOT_USER=smartview
MINIO_ROOT_PASSWORD=smartview_minio_password
MINIO_ENDPOINT=http://localhost:9000
MINIO_CONSOLE_URL=http://localhost:9001
MINIO_REGION=us-east-1

CHROMA_HOST=localhost
CHROMA_PORT=8001
CHROMA_URL=http://localhost:8001
```

- [ ] **Step 3: Create `smartview-infra/mysql/init/01-ensure-database.sh`**

Use a shell script because the official MySQL image executes `.sql` files directly and does not interpolate environment variables inside them. The guard clause prevents unsafe database identifiers from being interpolated into SQL.

```sh
#!/usr/bin/env bash
set -euo pipefail

# 该脚本只会在 MySQL 数据卷首次初始化时执行。
# 官方镜像负责创建用户和授权；这里补充字符集约束和数据库名安全校验。
database_name="${MYSQL_DATABASE:-smartview}"

if [[ ! "${database_name}" =~ ^[A-Za-z0-9_]+$ ]]; then
  echo "MYSQL_DATABASE 只能包含字母、数字和下划线" >&2
  exit 1
fi

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
ALTER DATABASE \`${database_name}\`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
SQL
```

- [ ] **Step 4: Create `smartview-infra/mysql/init/README.md`**

```markdown
# MySQL 初始化脚本

本目录会挂载到 MySQL 官方镜像的 `/docker-entrypoint-initdb.d/`，仅在 `mysql_data` 数据卷首次初始化时执行。

当前脚本用于校验 `MYSQL_DATABASE` 的命名安全性，并确保应用数据库使用 `utf8mb4` 字符集和 `utf8mb4_0900_ai_ci` 排序规则。账号创建、密码设置和授权由 MySQL 官方镜像根据环境变量完成。

如果需要重新执行初始化脚本，需要先停止 Compose 并删除 `mysql_data` 数据卷；这会清空本地数据库数据。
```

- [ ] **Step 5: Create `smartview-infra/minio/README.md`**

```markdown
# MinIO 本地开发说明

MinIO API 默认地址为 `http://localhost:9000`，Console 默认地址为 `http://localhost:9001`。

默认本地登录账号来自 `.env.example`：

- 用户名：`smartview`
- 密码：`smartview_minio_password`

本任务只提供对象存储基础服务和登录能力，不自动创建业务存储桶。后续如果需要初始化存储桶或策略，应在本目录新增脚本，并在计划中明确是否通过长期运行服务、一次性初始化容器或手动命令执行。
```

- [ ] **Step 6: Modify `.gitignore`**

Add the keep rule directly after `.env` entries:

```gitignore
!.env.example
!**/.env.example
```

- [ ] **Step 7: Create `.gitattributes`**

```gitattributes
smartview-infra/mysql/init/*.sh text eol=lf
```

### Task 2: Static validation

**Files:**
- Validate: `smartview-infra/docker-compose.yml`
- Validate: `smartview-infra/.env.example`
- Validate: `.gitignore`

- [ ] **Step 1: Run Compose config validation from repository root**

```powershell
docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example config --quiet
```

Expected result: exit code `0`.

- [ ] **Step 2: Verify exact image tags are present**

```powershell
Select-String -Path smartview-infra/docker-compose.yml -Pattern 'latest|mysql:\d+(\.\d+)?$|redis:\d+(\.\d+)?(-alpine)?$|rabbitmq:\d+(\.\d+)?(-management)?$|chromadb/chroma:\d+(\.\d+)?$'
```

Expected result: no matches. If matches appear, replace non-exact tags with the exact tags from the design spec.

- [ ] **Step 3: Verify health-check tools exist in pinned images**

```powershell
docker run --rm --entrypoint sh minio/minio:RELEASE.2025-09-07T16-13-09Z -c 'command -v curl'
docker run --rm --entrypoint sh chromadb/chroma:1.5.9 -c 'command -v bash && command -v head && command -v grep'
```

Expected result: MinIO prints a `curl` path; Chroma prints `bash`、`head`、`grep` paths. If either command fails, update the Compose health check to use a confirmed tool before continuing.

- [ ] **Step 4: Verify `.env.example` remains trackable**

```powershell
git check-ignore -v smartview-infra/.env.example
```

Expected result: no output and non-zero exit code, meaning the example file is not ignored.

### Task 3: Runtime validation

**Files:**
- Validate runtime behavior from `smartview-infra/docker-compose.yml`

- [ ] **Step 1: Pull images**

```powershell
docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example pull
```

Expected result: all five exact images pull successfully.

- [ ] **Step 2: Start services and wait for health**

```powershell
docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example up -d --wait
```

Expected result: exit code `0`; all five long-running services are started.

- [ ] **Step 3: Inspect container health**

```powershell
docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example ps
```

Expected result: `mysql`、`redis`、`rabbitmq`、`minio`、`chroma` all show healthy status.

- [ ] **Step 4: Verify service connectivity**

```powershell
docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example exec -T mysql mysql -usmartview -psmartview_password -D smartview -e "SELECT 1 AS ok; SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='smartview';"
docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example exec -T redis redis-cli -a smartview_redis_password ping
docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example exec -T rabbitmq rabbitmq-diagnostics -q ping
Invoke-WebRequest -UseBasicParsing -Uri http://localhost:15672/api/overview -Credential (New-Object System.Management.Automation.PSCredential('smartview',(ConvertTo-SecureString 'smartview_rabbitmq_password' -AsPlainText -Force)))
Invoke-WebRequest -UseBasicParsing -Uri http://localhost:9000/minio/health/live
Invoke-WebRequest -UseBasicParsing -Uri http://localhost:9001
Invoke-WebRequest -UseBasicParsing -Uri http://localhost:8001/api/v2/heartbeat
```

Expected result: MySQL returns `ok=1` and `utf8mb4` metadata; Redis returns `PONG`; RabbitMQ diagnostics succeeds; RabbitMQ management, MinIO API, MinIO Console and Chroma heartbeat return HTTP success responses.

### Task 4: Review, fix, commit, and sync

**Files:**
- Review all changed files for Task 1.1.
- Create when needed: `docs/errors/task_1.1_docker_compose_infrastructure_plan_errors.md`

- [ ] **Step 1: Request fresh sub-agent code review**

Ask a new sub-agent to compare implementation against:

- User requirements for Task 1.1.
- `docs/superpowers/specs/2026-07-17-docker-compose-infrastructure-design.md`
- `docs/plans/task_1.1_docker_compose_infrastructure_plan.md`

Reviewer must focus on Docker Compose correctness, health checks, environment variable completeness, `.gitignore` safety, and whether runtime validation evidence covers the acceptance criteria.

- [ ] **Step 2: If review finds issues, create fix plan before editing**

Create `docs/errors/task_1.1_docker_compose_infrastructure_plan_errors.md` with:

```markdown
# Task 1.1 Docker Compose 基础设施修复计划

## 审查发现

记录子 Agent 提出的每个问题、严重级别和影响。

## 修复步骤

列出每个问题对应的文件、修改方式、验证命令和预期结果。

## 复审记录

记录修复后的复审结论；如果复审发现新问题，追加到本文件。
```

- [ ] **Step 3: Re-run validation after fixes**

Run the static and runtime validation commands from Task 2 and Task 3 again. Final answer must include command names, result summary and uncovered risk if any.

- [ ] **Step 4: Commit only Task 1.1 files**

```powershell
git status --short
git add .gitattributes .gitignore docs/plans/task_1.1_docker_compose_infrastructure_plan.md smartview-infra/docker-compose.yml smartview-infra/.env.example smartview-infra/mysql/init/01-ensure-database.sh smartview-infra/mysql/init/README.md smartview-infra/minio/README.md
if (Test-Path docs/errors/task_1.1_docker_compose_infrastructure_plan_errors.md) { git add docs/errors/task_1.1_docker_compose_infrastructure_plan_errors.md }
git commit -m "feat: 添加 Docker Compose 基础设施"
```

- [ ] **Step 5: Push both configured remotes**

```powershell
$githubUrl = git remote get-url github 2>$null
if ($LASTEXITCODE -ne 0) { git remote add github git@github.com:PHJ20030616/SmartView.git } elseif ($githubUrl -ne 'git@github.com:PHJ20030616/SmartView.git') { git remote set-url github git@github.com:PHJ20030616/SmartView.git }
$giteeUrl = git remote get-url gitee 2>$null
if ($LASTEXITCODE -ne 0) { git remote add gitee https://gitee.com/phj20030616/smart-view.git } elseif ($giteeUrl -ne 'https://gitee.com/phj20030616/smart-view.git') { git remote set-url gitee https://gitee.com/phj20030616/smart-view.git }
git remote -v
git push github master
git push gitee master
```

Expected result: both remotes accept the new commits.

## Acceptance Checklist

- [ ] `smartview-infra/docker-compose.yml` starts MySQL, Redis, RabbitMQ, MinIO and Chroma.
- [ ] All services use fixed, exact image tags.
- [ ] Host ports default to `3306`、`6379`、`5672`、`15672`、`9000`、`9001`、`8001`.
- [ ] `.env.example` lists all necessary connection variables for Spring Boot and FastAPI.
- [ ] `smartview-infra/mysql/init/` exists and contains documented initialization logic.
- [ ] `smartview-infra/minio/` exists and contains documented MinIO local usage.
- [ ] `docker compose -f smartview-infra/docker-compose.yml --env-file smartview-infra/.env.example up -d --wait` reports healthy services.
- [ ] RabbitMQ management endpoint is accessible with sample credentials.
- [ ] MinIO Console is reachable and root credentials are documented.
- [ ] Final report includes exact test commands and results.
