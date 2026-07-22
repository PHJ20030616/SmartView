# SmartView 生产环境配置指南

## 1. 概述

### 1.1 项目架构

SmartView 采用前后端分离架构：

- **前端**：React + TypeScript + Vite，构建后生成静态文件
- **后端**：Spring Boot，提供 RESTful API
- **数据库**：MySQL
- **对象存储**：MinIO
- **消息队列**：RabbitMQ
- **缓存**：Redis

### 1.2 开发环境 vs 生产环境

| 配置项 | 开发环境 | 生产环境 |
|--------|----------|----------|
| 跨域处理 | Vite Dev Server 代理 | Nginx 反向代理 |
| 前端访问方式 | http://localhost:5173 | https://smartview.com |
| 后端访问方式 | http://localhost:8080 | 通过 Nginx 代理，不直接暴露 |
| HTTPS | 不需要 | 必须启用 |
| 环境变量 | 可使用默认值 | 必须显式配置 |

### 1.3 关键配置变更点

1. **前端**：Vite 代理被 Nginx 反向代理替代
2. **后端**：所有敏感配置必须通过环境变量传递
3. **网络**：前后端统一在同一域名下，无跨域问题
4. **安全**：启用 HTTPS，使用强密码和密钥

---

## 2. 前端部署

### 2.1 构建配置

**Step 1: 安装依赖**

```bash
cd smartview-web
npm install
```

**Step 2: 生产构建**

```bash
npm run build
```

构建产物将输出到 `dist/` 目录。

**Step 3: 验证构建产物**

```bash
ls -lh dist/
# 应包含：index.html、assets/（JS、CSS 等静态资源）
```

### 2.2 环境变量配置（可选）

如果前后端不在同一域名下，需要配置后端地址：

```env
# .env.production
VITE_API_BASE_URL=https://api.smartview.com
```

**重要说明**：
- ✅ **推荐方案**：使用 Nginx 反向代理，前后端在同一域名下，前端无需配置 `VITE_API_BASE_URL`
- ❌ **不推荐**：前后端分离部署在不同域名，需要配置 CORS 和 `VITE_API_BASE_URL`

### 2.3 部署到静态文件服务器

**Step 1: 部署构建产物**

```bash
# 将 dist/ 目录内容复制到 Web 服务器
sudo cp -r dist/* /var/www/smartview-web/

# 设置正确的文件权限
sudo chown -R www-data:www-data /var/www/smartview-web/
sudo chmod -R 755 /var/www/smartview-web/
```

**Step 2: 配置 Nginx**（详见第 4 节）

---

## 3. 后端部署

### 3.1 环境变量配置

**必需的环境变量：**

```bash
# 数据库配置
export MYSQL_JDBC_URL="jdbc:mysql://localhost:3306/smartview?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
export MYSQL_USER=smartview
export MYSQL_ROOT_PASSWORD=your_secure_mysql_password_here

# Redis 配置
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=your_secure_redis_password_here
export REDIS_DATABASE=0

# RabbitMQ 配置
export RABBITMQ_HOST=localhost
export RABBITMQ_AMQP_PORT=5672
export RABBITMQ_DEFAULT_USER=smartview
export RABBITMQ_DEFAULT_PASS=your_secure_rabbitmq_password_here
export RABBITMQ_DEFAULT_VHOST=smartview

# MinIO 配置
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ROOT_USER=smartview
export MINIO_ROOT_PASSWORD=your_secure_minio_password_here
export MINIO_BUCKET=smartview

# JWT 配置（必需，无默认值）
export JWT_SECRET=your_jwt_secret_min_32_characters_long_random_string_here
export JWT_ISSUER=smartview
export JWT_ACCESS_TOKEN_TTL_SECONDS=7200

# 可选：服务器端口
export SERVER_PORT=8080
```

**安全要求：**

⚠️ **强制要求：**
- `JWT_SECRET` 必须至少 32 字符，使用强随机字符串
- 所有密码必须使用强密码（大小写字母+数字+特殊字符，16+ 字符）
- 生产环境禁止使用示例密码或默认密码
- 禁止将密码硬编码或提交到版本控制

### 3.2 生成 JWT Secret

JWT Secret 是认证系统的核心密钥，必须使用强随机字符串。

**方法 1：使用 OpenSSL（推荐）**

```bash
openssl rand -base64 48
```

**方法 2：使用 /dev/urandom（Linux）**

```bash
head -c 48 /dev/urandom | base64
```

**方法 3：使用 Python**

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

**示例输出：**

```
3K8vN2xP9mR5tY7wQ1sA4dF6gH8jL0nZ3xB5cV7mN9pR2tY4wQ6sA8dF
```

### 3.3 环境变量管理最佳实践

**方法 1：使用环境变量文件（推荐用于测试）**

创建 `/etc/smartview/env` 文件：

```bash
# /etc/smartview/env
MYSQL_JDBC_URL=jdbc:mysql://localhost:3306/smartview?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
MYSQL_USER=smartview
MYSQL_ROOT_PASSWORD=strong_mysql_password
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=strong_redis_password
REDIS_DATABASE=0
RABBITMQ_HOST=localhost
RABBITMQ_AMQP_PORT=5672
RABBITMQ_DEFAULT_USER=smartview
RABBITMQ_DEFAULT_PASS=strong_rabbitmq_password
RABBITMQ_DEFAULT_VHOST=smartview
MINIO_ENDPOINT=http://localhost:9000
MINIO_ROOT_USER=smartview
MINIO_ROOT_PASSWORD=strong_minio_password
MINIO_BUCKET=smartview
JWT_SECRET=your_48_character_random_jwt_secret_here
JWT_ISSUER=smartview
JWT_ACCESS_TOKEN_TTL_SECONDS=7200
SERVER_PORT=8080
```

设置文件权限：

```bash
sudo chmod 600 /etc/smartview/env
sudo chown smartview:smartview /etc/smartview/env
```

**方法 2：使用 systemd 环境变量（推荐用于生产）**

在 systemd service 文件中配置环境变量（见第 3.5 节）。

### 3.4 构建后端应用

**Step 1: 构建 JAR 包**

```bash
cd smartview-server
mvn clean package -DskipTests
```

构建产物：`target/smartview-server-0.1.0-SNAPSHOT.jar`

**Step 2: 验证构建产物**

```bash
ls -lh target/*.jar
# 应看到 smartview-server-0.1.0-SNAPSHOT.jar
```

### 3.5 启动后端服务

**方法 1：直接启动（测试环境）**

```bash
# 加载环境变量
source /etc/smartview/env

# 启动服务
cd smartview-server
java -jar target/smartview-server-0.1.0-SNAPSHOT.jar
```

**方法 2：使用 systemd（生产环境推荐）**

创建 systemd service 文件：

```bash
sudo nano /etc/systemd/system/smartview-server.service
```

配置内容：

```ini
[Unit]
Description=SmartView Backend Service
After=mysql.service redis.service rabbitmq-server.service

[Service]
Type=simple
User=smartview
Group=smartview
WorkingDirectory=/opt/smartview/server
ExecStart=/usr/bin/java -jar /opt/smartview/server/smartview-server-0.1.0-SNAPSHOT.jar

# 环境变量配置
Environment="MYSQL_JDBC_URL=jdbc:mysql://localhost:3306/smartview?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
Environment="MYSQL_USER=smartview"
Environment="MYSQL_ROOT_PASSWORD=your_password"
Environment="REDIS_HOST=localhost"
Environment="REDIS_PORT=6379"

Environment="REDIS_PASSWORD=your_redis_password"
Environment="REDIS_DATABASE=0"
Environment="RABBITMQ_HOST=localhost"
Environment="RABBITMQ_AMQP_PORT=5672"
Environment="RABBITMQ_DEFAULT_USER=smartview"
Environment="RABBITMQ_DEFAULT_PASS=your_rabbitmq_password"
Environment="RABBITMQ_DEFAULT_VHOST=smartview"
Environment="MINIO_ENDPOINT=http://localhost:9000"
Environment="MINIO_ROOT_USER=smartview"
Environment="MINIO_ROOT_PASSWORD=your_minio_password"
Environment="MINIO_BUCKET=smartview"
Environment="JWT_SECRET=your_jwt_secret_here"
Environment="JWT_ISSUER=smartview"
Environment="JWT_ACCESS_TOKEN_TTL_SECONDS=7200"
Environment="SERVER_PORT=8080"

# 日志配置
StandardOutput=journal
StandardError=journal
SyslogIdentifier=smartview-server

# 重启策略
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

**启动服务：**

```bash
# 重新加载 systemd 配置
sudo systemctl daemon-reload

# 启动服务
sudo systemctl start smartview-server

# 设置开机自启
sudo systemctl enable smartview-server

# 查看服务状态
sudo systemctl status smartview-server

# 查看日志
sudo journalctl -u smartview-server -f
```

---

## 4. Nginx 反向代理配置

**核心配置原则：**
- 前端和后端统一在一个域名下（如 `smartview.com`）
- `/` 路径访问前端静态文件
- `/api/` 路径反向代理到后端服务
- 无需配置 CORS，因为不存在跨域请求

### 4.1 基础配置（HTTP）

创建 Nginx 配置文件：

```bash
sudo nano /etc/nginx/sites-available/smartview
```

配置内容：

```nginx
server {
    listen 80;
    server_name smartview.com www.smartview.com;
    
    # 前端静态文件
    location / {
        root /var/www/smartview-web;
        try_files $uri $uri/ /index.html;
        
        # SPA 路由支持：所有未匹配的请求返回 index.html
        # 由前端路由处理
    }
    
    # 后端 API 反向代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # 超时配置
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }
}
```

**启用配置：**

```bash
# 创建软链接
sudo ln -s /etc/nginx/sites-available/smartview /etc/nginx/sites-enabled/

# 测试配置
sudo nginx -t

# 重启 Nginx
sudo systemctl restart nginx
```

### 4.2 HTTPS 配置（生产环境推荐）

**Step 1: 获取 SSL 证书**

使用 Let's Encrypt 免费证书：

```bash
# 安装 certbot
sudo apt update
sudo apt install certbot python3-certbot-nginx

# 自动获取证书并配置 Nginx
sudo certbot --nginx -d smartview.com -d www.smartview.com
```

**Step 2: 手动 HTTPS 配置**

如果使用自签名证书或商业证书，手动配置：

```nginx
server {
    listen 443 ssl http2;
    server_name smartview.com www.smartview.com;
    
    # SSL 证书配置
    ssl_certificate /etc/nginx/ssl/smartview.crt;
    ssl_certificate_key /etc/nginx/ssl/smartview.key;
    
    # SSL 安全配置
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:SSL:10m;
    ssl_session_timeout 10m;
    
    # 前端静态文件
    location / {
        root /var/www/smartview-web;
        try_files $uri $uri/ /index.html;
        
        # 静态资源缓存配置
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
        
        # HTML 文件不缓存
        location ~* \.html$ {
            expires -1;
            add_header Cache-Control "no-cache, no-store, must-revalidate";
        }
    }
    
    # 后端 API 反向代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        
        # 超时配置
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
        
        # 缓冲配置
        proxy_buffering on;
        proxy_buffer_size 4k;
        proxy_buffers 8 4k;
    }
}

# HTTP 重定向到 HTTPS
server {
    listen 80;
    server_name smartview.com www.smartview.com;
    return 301 https://$server_name$request_uri;
}
```

### 4.3 负载均衡配置（可选）

如果部署多个后端实例，配置负载均衡：

```nginx
# 定义后端服务器组
upstream smartview_backend {
    # 负载均衡策略：默认为轮询（round-robin）
    # least_conn;  # 最少连接数策略
    # ip_hash;     # IP 哈希策略（会话保持）
    
    server 127.0.0.1:8080 weight=1 max_fails=3 fail_timeout=30s;
    server 127.0.0.1:8081 weight=1 max_fails=3 fail_timeout=30s;
    server 127.0.0.1:8082 weight=1 max_fails=3 fail_timeout=30s;
    
    # 健康检查（需要 nginx_upstream_check_module）
    # check interval=3000 rise=2 fall=3 timeout=1000 type=http;
    # check_http_send "HEAD /api/health HTTP/1.0\r\n\r\n";
    # check_http_expect_alive http_2xx http_3xx;
}

server {
    listen 443 ssl http2;
    server_name smartview.com www.smartview.com;
    
    # ... SSL 配置 ...
    
    # 前端静态文件
    location / {
        root /var/www/smartview-web;
        try_files $uri $uri/ /index.html;
    }
    
    # 后端 API 反向代理到负载均衡组
    location /api/ {
        proxy_pass http://smartview_backend/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        
        # 超时配置
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
        
        # 重试配置
        proxy_next_upstream error timeout http_500 http_502 http_503 http_504;
        proxy_next_upstream_tries 2;
    }
}
```

---

## 5. 部署验证清单

部署完成后，按以下清单逐项验证。

### 5.1 前端验证

- [ ] 访问 `https://smartview.com/` 显示登录页面
- [ ] 浏览器开发者工具 Console 无错误
- [ ] 浏览器开发者工具 Network 无 404 错误
- [ ] 静态资源（JS、CSS、图片、字体）正常加载
- [ ] SPA 路由正常工作（访问 `/login`、`/register` 等路径）
- [ ] 刷新页面不会出现 404 错误

### 5.2 后端验证

- [ ] 后端服务正常启动，检查日志：`sudo journalctl -u smartview-server -n 50`
- [ ] 数据库连接正常（启动日志中无数据库连接错误）
- [ ] Redis 连接正常（启动日志中无 Redis 连接错误）
- [ ] RabbitMQ 连接正常（启动日志中无 RabbitMQ 连接错误）
- [ ] MinIO 连接正常（启动日志中无 MinIO 连接错误）
- [ ] 服务监听 8080 端口：`netstat -tlnp | grep 8080`

### 5.3 认证功能验证

- [ ] 用户可以成功注册（访问 `/register`，填写表单并提交）
- [ ] 用户可以成功登录（访问 `/login`，输入用户名密码并提交）
- [ ] 登录成功后 Token 存储在 localStorage（开发者工具 Application 标签查看）
- [ ] 登录后可以访问受保护页面
- [ ] 未登录访问受保护页面会被重定向到登录页
- [ ] 退出登录后 Token 被清除

### 5.4 网络验证

- [ ] 浏览器 Network 标签中，`/api/*` 请求成功返回（状态码 2xx 或 4xx）
- [ ] 无 CORS 错误（Console 中无 `Access-Control-Allow-Origin` 相关错误）
- [ ] 请求 URL 正确（如 `https://smartview.com/api/auth/login`，而不是 `http://localhost:8080/...`）
- [ ] 响应时间在合理范围内（大多数请求 < 500ms）

### 5.5 HTTPS 验证

- [ ] 浏览器地址栏显示锁图标
- [ ] 证书有效且未过期
- [ ] HTTP 请求自动重定向到 HTTPS
- [ ] 所有资源通过 HTTPS 加载（无混合内容警告）

---

## 6. 安全最佳实践

### 6.1 强制要求

#### 6.1.1 JWT Secret 管理

✅ **必须做：**
- 使用 32+ 字符的强随机字符串
- 通过环境变量传递，禁止硬编码
- 定期轮换（建议每季度）
- 限制环境变量文件访问权限（`chmod 600`）

❌ **禁止做：**
- 在代码中设置默认值
- 将 secret 提交到版本控制
- 使用简单密码或可预测字符串
- 多个环境使用相同的 secret

#### 6.1.2 数据库密码管理

✅ **必须做：**
- 使用强密码（大小写字母+数字+特殊字符，16+ 字符）
- 创建专用数据库用户，只授予必要权限（不要使用 root）
- 限制数据库远程访问（只允许应用服务器 IP）

❌ **禁止做：**
- 使用默认密码（如 `smartview_password`）
- 使用弱密码或字典单词
- 将密码硬编码在配置文件中

**创建专用数据库用户：**

```sql
-- 创建数据库
CREATE DATABASE smartview CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建专用用户
CREATE USER 'smartview'@'localhost' IDENTIFIED BY 'your_strong_password_here';

-- 授予必要权限
GRANT SELECT, INSERT, UPDATE, DELETE ON smartview.* TO 'smartview'@'localhost';

-- 刷新权限
FLUSH PRIVILEGES;
```

#### 6.1.3 HTTPS 配置

✅ **必须做：**
- 生产环境必须启用 HTTPS
- 使用 TLS 1.2 或更高版本
- HTTP 请求强制重定向到 HTTPS
- 配置 HSTS 头部（HTTP Strict Transport Security）

**HSTS 配置示例：**

```nginx
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
```

#### 6.1.4 环境变量存储

✅ **推荐方案：**
- 使用环境变量或密钥管理系统（如 HashiCorp Vault、AWS Secrets Manager）
- systemd service 文件中配置环境变量
- 限制文件访问权限（`chmod 600`，只有服务账户可读）

❌ **禁止做：**
- 将 `.env` 文件提交到版本控制
- 在公共位置存储环境变量文件
- 在日志中打印敏感信息

### 6.2 推荐实践

#### 6.2.1 防火墙配置

只开放必要端口：

```bash
# 允许 HTTP 和 HTTPS
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp

# 允许 SSH（远程管理）
sudo ufw allow 22/tcp

# 启用防火墙
sudo ufw enable

# 查看状态
sudo ufw status
```

#### 6.2.2 服务隔离

- 后端服务不要直接暴露在公网，只监听 127.0.0.1
- 数据库、Redis、RabbitMQ 只监听本地或内网地址
- 使用专用系统用户运行应用（不要用 root）

**创建专用系统用户：**

```bash
# 创建系统用户
sudo useradd -r -s /bin/false smartview

# 创建应用目录
sudo mkdir -p /opt/smartview/server
sudo chown -R smartview:smartview /opt/smartview
```

#### 6.2.3 日志管理

- 启用 Nginx 访问日志和错误日志
- 配置日志轮转（logrotate）
- 定期检查日志中的异常访问模式
- 不要在日志中记录敏感信息（密码、Token 等）

**Nginx 日志配置：**

```nginx
access_log /var/log/nginx/smartview-access.log;
error_log /var/log/nginx/smartview-error.log warn;
```

#### 6.2.4 定期维护

- 定期更新系统和依赖包的安全补丁
- 定期备份数据库
- 定期检查 SSL 证书有效期
- 定期审计访问日志
- 定期轮换密钥和密码

---

## 7. 常见问题排查

### 7.1 前端页面刷新后 404

**症状：** 访问 `https://smartview.com/login` 正常，但刷新页面后显示 404。

**原因：** Nginx 未配置 SPA 路由支持，直接查找 `/login` 文件导致 404。

**解决方案：**

在 Nginx 配置的 `location /` 块中添加：

```nginx
try_files $uri $uri/ /index.html;
```

完整示例：

```nginx
location / {
    root /var/www/smartview-web;
    try_files $uri $uri/ /index.html;
}
```

### 7.2 API 请求 502 Bad Gateway

**症状：** 前端页面正常显示，但 API 请求返回 502 错误。

**原因：** 后端服务未启动或端口不匹配。

**排查步骤：**

```bash
# 1. 检查后端服务是否运行
sudo systemctl status smartview-server

# 2. 检查端口是否监听
netstat -tlnp | grep 8080
# 或
sudo lsof -i :8080

# 3. 检查后端日志
sudo journalctl -u smartview-server -n 50

# 4. 测试后端服务
curl http://127.0.0.1:8080/api/health
```

**解决方案：**
- 启动后端服务：`sudo systemctl start smartview-server`
- 检查环境变量配置是否正确
- 检查 Nginx 配置中的 `proxy_pass` 地址和端口

### 7.3 JWT 验证失败

**症状：** 登录成功，但后续 API 请求返回 401 Unauthorized。

**原因：** JWT_SECRET 不一致、未设置或 Token 格式错误。

**排查步骤：**

```bash
# 1. 检查环境变量是否设置
sudo systemctl show smartview-server | grep JWT_SECRET

# 2. 检查后端启动日志
sudo journalctl -u smartview-server | grep -i jwt

# 3. 检查浏览器 localStorage
# 打开浏览器开发者工具 -> Application -> Local Storage
# 查看是否存在 token 键
```

**解决方案：**
- 确保 `JWT_SECRET` 环境变量已设置且长度 >= 32 字符
- 重启后端服务：`sudo systemctl restart smartview-server`
- 清除浏览器 localStorage 并重新登录

### 7.4 CORS 错误

**症状：** 浏览器 Console 显示 CORS 错误。

```
Access to XMLHttpRequest at 'http://localhost:8080/api/auth/login' from origin 'https://smartview.com' has been blocked by CORS policy
```

**原因：** Nginx 反向代理配置错误，前端直接访问后端服务。

**排查步骤：**

1. 检查浏览器 Network 标签中的请求 URL
   - ✅ 正确：`https://smartview.com/api/auth/login`
   - ❌ 错误：`http://localhost:8080/api/auth/login`

2. 检查前端 API Client 配置（`src/api/client.ts`）
   - 确保 `baseURL` 为 `/api` 或未设置（使用相对路径）

3. 检查 Nginx 配置
   - 确保有 `location /api/` 代理配置

**解决方案：**
- 确保前后端在同一域名下，通过 Nginx 代理访问
- 前端 API Client 使用相对路径 `/api`
- 不要配置 `VITE_API_BASE_URL` 环境变量

### 7.5 静态资源 404

**症状：** HTML 加载正常，但 JS、CSS 文件 404。

**原因：** Nginx root 路径配置错误或文件权限问题。

**排查步骤：**

```bash
# 1. 检查文件是否存在
ls -la /var/www/smartview-web/

# 2. 检查文件权限
ls -la /var/www/smartview-web/assets/

# 3. 检查 Nginx 配置
sudo nginx -T | grep -A 10 "location /"

# 4. 检查 Nginx 错误日志
sudo tail -f /var/log/nginx/error.log
```

**解决方案：**
- 确保构建产物正确部署到 `/var/www/smartview-web/`
- 设置正确的文件权限：`sudo chmod -R 755 /var/www/smartview-web/`
- 检查 Nginx 配置中的 `root` 路径

### 7.6 数据库连接失败

**症状：** 后端启动失败，日志显示数据库连接错误。

**排查步骤：**

```bash
# 1. 检查 MySQL 服务状态
sudo systemctl status mysql

# 2. 测试数据库连接
mysql -h localhost -u smartview -p

# 3. 检查数据库是否存在
mysql -u root -p -e "SHOW DATABASES;"

# 4. 检查用户权限
mysql -u root -p -e "SHOW GRANTS FOR 'smartview'@'localhost';"
```

**解决方案：**
- 启动 MySQL 服务：`sudo systemctl start mysql`
- 创建数据库和用户（见第 6.1.2 节）
- 检查环境变量中的数据库配置

---

## 8. 回滚方案

如果部署出现严重问题，快速回滚到上一个稳定版本。

### 8.1 前端回滚

```bash
# 备份当前版本
sudo mv /var/www/smartview-web /var/www/smartview-web-failed

# 恢复上一个版本
sudo cp -r /var/www/smartview-web-backup /var/www/smartview-web

# 重启 Nginx
sudo systemctl restart nginx
```

**建议：** 每次部署前备份当前版本

```bash
# 部署前备份
sudo cp -r /var/www/smartview-web /var/www/smartview-web-backup-$(date +%Y%m%d-%H%M%S)
```

### 8.2 后端回滚

```bash
# 停止当前版本
sudo systemctl stop smartview-server

# 恢复上一个版本的 JAR 包
sudo cp /opt/smartview/server/smartview-server-backup.jar /opt/smartview/server/smartview-server-0.1.0-SNAPSHOT.jar

# 启动服务
sudo systemctl start smartview-server

# 检查状态
sudo systemctl status smartview-server
```

### 8.3 数据库回滚

如果有数据库迁移：

```bash
# 查看当前迁移版本
# 登录数据库查看 flyway_schema_history 表

# 恢复数据库备份（如果需要）
mysql -u root -p smartview < /backup/smartview-backup-20260722.sql
```

**建议：** 每次部署前备份数据库

```bash
# 备份数据库
mysqldump -u root -p smartview > smartview-backup-$(date +%Y%m%d-%H%M%S).sql
```

---

## 9. 部署检查清单

在生产环境部署前，确保完成以下检查：

### 9.1 部署前检查

- [ ] 所有环境变量已配置且使用强密码
- [ ] JWT_SECRET 已生成（32+ 字符）
- [ ] 数据库已创建且专用用户已配置
- [ ] Redis、RabbitMQ、MinIO 已安装并正常运行
- [ ] SSL 证书已获取且有效
- [ ] 备份了当前版本（如果是更新部署）
- [ ] 备份了数据库

### 9.2 部署后检查

- [ ] 前端页面正常访问
- [ ] API 请求正常响应
- [ ] 用户可以注册和登录
- [ ] 无 CORS 错误
- [ ] 无 404 或 502 错误
- [ ] HTTPS 正常工作
- [ ] 后端日志无异常
- [ ] 服务设置为开机自启

### 9.3 安全检查

- [ ] 防火墙已配置，只开放必要端口
- [ ] 后端服务不直接暴露在公网
- [ ] 数据库只监听本地或内网
- [ ] 环境变量文件权限正确（chmod 600）
- [ ] 使用专用系统用户运行服务（不是 root）
- [ ] SSL 配置正确，使用 TLS 1.2+
- [ ] HTTP 强制重定向到 HTTPS

---

## 10. 总结

本文档提供了 SmartView 项目的完整生产环境部署指南，核心要点：

1. **前后端统一域名**：通过 Nginx 反向代理，避免跨域问题
2. **环境变量管理**：所有敏感配置通过环境变量传递，不硬编码
3. **安全优先**：使用强密码、启用 HTTPS、限制访问权限
4. **完整验证**：部署后按清单逐项验证功能
5. **故障处理**：提供常见问题排查和回滚方案

如有问题，请参考第 7 节常见问题排查。

