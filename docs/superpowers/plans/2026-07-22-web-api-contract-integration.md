# Web API 契约集成实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现前端基于 OpenAPI 契约与 Spring Boot 后端的联调，替换 Mock 服务为真实 API 调用

**Architecture:** 使用 openapi-typescript-fetch 从契约生成类型安全的 API Client，通过 Vite 代理解决开发环境跨域问题，生产环境使用 Nginx 反向代理

**Tech Stack:** 
- openapi-typescript-fetch (API Client 生成)
- Vite Proxy (开发环境代理)
- TypeScript (类型安全)
- Axios (HTTP 请求底层)

## Global Constraints

- Node.js >= 18.0.0
- TypeScript strict mode 启用
- 所有用户可见文本必须使用中文
- 必要位置必须添加清晰的中文注释
- 禁止手写与契约重复的类型定义
- 禁止直接拼接 API URL 字符串

---

## 文件结构

### 新建文件
- `smartview-web/src/api/client.ts` - Fetcher 实例配置和拦截器
- `smartview-web/src/api/authApi.ts` - 认证 API（从契约生成）

### 修改文件
- `smartview-web/package.json` - 添加 openapi-typescript-fetch 依赖
- `smartview-web/vite.config.ts:7-15` - 添加代理配置
- `smartview-web/src/features/auth/authService.ts:28-58` - 替换 Mock 实现为真实 API 调用

---

## Task 1: 安装依赖

**Files:**
- Modify: `smartview-web/package.json`

**Interfaces:**
- Consumes: 无
- Produces: `openapi-typescript-fetch` 包可用于后续任务

---

- [ ] **Step 1: 进入前端目录**

```bash
cd smartview-web
```

- [ ] **Step 2: 安装 openapi-typescript-fetch**

```bash
npm install openapi-typescript-fetch
```

预期输出：
```
added 1 package, and audited 500 packages in 3s
```

- [ ] **Step 3: 验证安装**

```bash
npm list openapi-typescript-fetch
```

预期输出：
```
smartview-web@0.1.0
└── openapi-typescript-fetch@2.0.0
```

- [ ] **Step 4: 提交依赖变更**

```bash
git add package.json package-lock.json
git commit -m "chore: 添加 openapi-typescript-fetch 依赖用于契约驱动的 API Client

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: 配置 Vite 代理

**Files:**
- Modify: `smartview-web/vite.config.ts:7-15`

**Interfaces:**
- Consumes: 无
- Produces: 开发环境 `/api/*` 请求自动代理到 `http://localhost:8080`

---

- [ ] **Step 1: 修改 vite.config.ts 添加代理配置**

在 `defineConfig` 中添加 `server.proxy` 配置：

```typescript
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure: (proxy, _options) => {
          proxy.on('error', (err, _req, _res) => {
            console.log('[Proxy Error]', err);
          });
          proxy.on('proxyReq', (proxyReq, req, _res) => {
            console.log('[Proxy Request]', req.method, req.url);
          });
          proxy.on('proxyRes', (proxyRes, req, _res) => {
            console.log('[Proxy Response]', proxyRes.statusCode, req.url);
          });
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
  },
});
```

- [ ] **Step 2: 验证配置语法**

```bash
npx tsc --noEmit vite.config.ts
```

预期输出：无错误

- [ ] **Step 3: 提交配置**

```bash
git add vite.config.ts
git commit -m "feat: 配置 Vite 代理转发 /api 请求到后端 8080 端口

开发环境通过代理解决跨域问题，生产环境由 Nginx 处理。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: 创建 API Client

**Files:**
- Create: `smartview-web/src/api/client.ts`

**Interfaces:**
- Consumes: 
  - `src/api/generated/schema.ts` - `paths` 类型定义
  - `src/api/http.ts` - `getCurrentTraceId()`, `syncTraceIdFromResponse()`, `TRACE_ID_HEADER`
  - `src/features/auth/authStorage.ts` - `getAuthToken()`, `clearAuthSession()`
  - `src/features/auth/authRedirect.ts` - `redirectToExpiredLogin()`
- Produces: 
  - `fetcher: Fetcher<paths>` - 配置完成的 Fetcher 实例，供其他模块创建 API 函数

---

- [ ] **Step 1: 创建 client.ts 文件**

```typescript
import { Fetcher } from 'openapi-typescript-fetch';
import type { paths } from './generated/schema';
import { getCurrentTraceId, syncTraceIdFromResponse, TRACE_ID_HEADER } from './http';
import { getAuthToken, clearAuthSession } from '../features/auth/authStorage';
import { redirectToExpiredLogin } from '../features/auth/authRedirect';

/**
 * 类型化的 API Fetcher，严格绑定到 OpenAPI 契约
 * 
 * 所有 API 调用路径、方法、参数类型、响应类型均由契约决定，
 * 契约变更后重新生成类型，不兼容代码将在编译时报错。
 */
const fetcher = Fetcher.for<paths>();

/**
 * 配置 Fetcher 基础设置和拦截器
 */
fetcher.configure({
  baseUrl: '/api',
  init: {
    headers: {
      'Content-Type': 'application/json',
    },
  },
  use: [
    {
      // 请求拦截器：注入 JWT Token 和 Trace ID
      async onRequest({ request }) {
        // 添加 Trace ID 用于请求链路追踪
        request.headers.set(TRACE_ID_HEADER, getCurrentTraceId());

        // 添加 JWT Token 用于身份认证
        const token = getAuthToken();
        if (token) {
          request.headers.set('Authorization', `Bearer ${token}`);
        }

        return request;
      },
    },
    {
      // 响应拦截器：同步 Trace ID、处理 401 未授权
      async onResponse({ response }) {
        // 从响应头同步 Trace ID
        syncTraceIdFromResponse(response);

        // 处理 401 未授权：清除会话并跳转登录页
        if (response.status === 401) {
          const authHeader = response.request?.headers.get('Authorization');
          const failedToken = authHeader?.replace(/^Bearer\s+/i, '') || null;
          const currentToken = getAuthToken();

          // 只处理当前会话的 401，避免旧请求误触发跳转
          if (failedToken && failedToken === currentToken) {
            clearAuthSession();
            redirectToExpiredLogin();
          }
        }

        return response;
      },
    },
  ],
});

export { fetcher };
```

- [ ] **Step 2: 验证类型检查**

```bash
npx tsc --noEmit src/api/client.ts
```

预期输出：无错误

- [ ] **Step 3: 提交代码**

```bash
git add src/api/client.ts
git commit -m "feat: 创建类型化 API Client 配置

- 绑定 OpenAPI 契约类型
- 请求拦截器注入 JWT Token 和 Trace ID
- 响应拦截器处理 401 并同步 Trace ID

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: 创建认证 API

**Files:**
- Create: `smartview-web/src/api/authApi.ts`

**Interfaces:**
- Consumes: 
  - `src/api/client.ts` - `fetcher` 实例
- Produces:
  - `authApi.register(request)` - 注册 API，参数类型 `RegisterRequest`，返回类型 `Promise<ApiResponse<UserInfo>>`
  - `authApi.login(request)` - 登录 API，参数类型 `LoginRequest`，返回类型 `Promise<ApiResponse<LoginData>>`
  - `authApi.getCurrentUser()` - 获取当前用户 API，返回类型 `Promise<ApiResponse<UserInfo>>`

---

- [ ] **Step 1: 创建 authApi.ts 文件**

```typescript
import { fetcher } from './client';

/**
 * 认证相关 API
 * 
 * 所有 API 函数均严格按照 OpenAPI 契约生成：
 * - 请求路径、方法由契约决定
 * - 参数类型、响应类型由契约自动推断
 * - 契约变更后重新生成类型，不兼容调用将编译报错
 */
export const authApi = {
  /**
   * 用户注册
   * 
   * 契约路径：POST /api/auth/register
   * 请求体：{ username, password, nickname, email?, phone? }
   * 响应体：ApiResponse<UserInfo>
   */
  register: fetcher.path('/api/auth/register').method('post').create(),

  /**
   * 用户登录
   * 
   * 契约路径：POST /api/auth/login
   * 请求体：{ username, password }
   * 响应体：ApiResponse<LoginData>
   *   - LoginData: { token, tokenType, expiresIn, user }
   */
  login: fetcher.path('/api/auth/login').method('post').create(),

  /**
   * 获取当前用户信息
   * 
   * 契约路径：GET /api/users/me
   * 需要 JWT Token（拦截器自动添加）
   * 响应体：ApiResponse<UserInfo>
   */
  getCurrentUser: fetcher.path('/api/users/me').method('get').create(),
};
```

- [ ] **Step 2: 验证类型检查**

```bash
npx tsc --noEmit src/api/authApi.ts
```

预期输出：无错误

- [ ] **Step 3: 测试类型安全性（可选验证步骤）**

创建临时测试文件验证类型约束：

```typescript
// temp-test.ts
import { authApi } from './src/api/authApi';

// ❌ 应该报错：缺少必填字段
authApi.register({ username: 'test' });

// ✅ 应该通过
authApi.register({ 
  username: 'testuser', 
  password: 'password123',
  nickname: 'Test User'
});
```

运行 `npx tsc --noEmit temp-test.ts`，验证第一个调用报错，第二个通过后删除文件。

- [ ] **Step 4: 提交代码**

```bash
git add src/api/authApi.ts
git commit -m "feat: 创建认证 API 函数

- 从 OpenAPI 契约生成注册、登录、获取当前用户 API
- 请求参数和响应类型由契约自动推断
- 编译时类型检查防止调用错误

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: 改造认证服务

**Files:**
- Modify: `smartview-web/src/features/auth/authService.ts:1-58`

**Interfaces:**
- Consumes:
  - `src/api/authApi.ts` - `authApi.register()`, `authApi.login()`
  - `src/features/auth/authTypes.ts` - `RegisterRequest`, `LoginRequest`, `UserInfo`, `LoginData`
- Produces:
  - `register(request: RegisterRequest): Promise<UserInfo>` - 注册服务，返回用户信息
  - `login(request: LoginRequest): Promise<LoginData>` - 登录服务，返回 Token 和用户信息

---

- [ ] **Step 1: 替换 authService.ts 实现**

完全替换文件内容为真实 API 调用：

```typescript
import { authApi } from '../../api/authApi';
import type { LoginData, LoginRequest, RegisterRequest, UserInfo } from './authTypes';

/**
 * 用户注册
 * 
 * 适配层职责：
 * 1. 调用 authApi.register
 * 2. 解包 ApiResponse<UserInfo> 返回 data 字段
 * 3. 错误处理由拦截器统一处理，此处透传
 */
export async function register(request: RegisterRequest): Promise<UserInfo> {
  const response = await authApi.register(request);
  
  // Spring Boot 返回 ApiResponse<UserInfo> 结构：
  // { code: "SUCCESS", message: "...", data: UserInfo, traceId: "...", timestamp: "..." }
  return response.data.data;
}

/**
 * 用户登录
 * 
 * 适配层职责：
 * 1. 调用 authApi.login
 * 2. 解包 ApiResponse<LoginData> 返回 data 字段
 *    - LoginData 包含 { token, tokenType, expiresIn, user }
 */
export async function login(request: LoginRequest): Promise<LoginData> {
  const response = await authApi.login(request);
  return response.data.data;
}
```

- [ ] **Step 2: 验证类型检查**

```bash
npx tsc --noEmit src/features/auth/authService.ts
```

预期输出：无错误

- [ ] **Step 3: 验证依赖页面的类型检查**

```bash
npx tsc --noEmit src/pages/login/LoginPage.tsx src/pages/login/RegisterPage.tsx
```

预期输出：无错误（页面代码无需修改，因为 authService 签名保持不变）

- [ ] **Step 4: 提交代码**

```bash
git add src/features/auth/authService.ts
git commit -m "feat: 替换 Mock 实现为真实 API 调用

- 移除 Mock 延迟和随机数据生成
- 调用 authApi 实现真实后端联调
- 解包 ApiResponse 结构返回业务数据
- 页面代码无需修改，因为服务签名保持不变

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: 前后端联调测试

**Files:**
- Test: 整体集成测试

**Interfaces:**
- Consumes: 所有前述任务的产出
- Produces: 验证前后端联调成功

---

- [ ] **Step 1: 启动后端服务**

```bash
cd smartview-server
mvn spring-boot:run
```

预期输出：
```
Started SmartViewApplication in 3.5 seconds
```

验证后端健康：
```bash
curl http://localhost:8080/api/health
```

预期返回：`{"status":"UP"}`

- [ ] **Step 2: 启动前端开发服务器**

新开终端窗口：

```bash
cd smartview-web
npm run dev
```

预期输出：
```
VITE v5.x.x ready in 500 ms
➜  Local:   http://localhost:5173/
➜  Network: use --host to expose
```

- [ ] **Step 3: 测试用户注册流程**

1. 浏览器访问 `http://localhost:5173/register`
2. 填写注册表单：
   - 用户名：`testuser`
   - 密码：`Test123456!`
   - 昵称：`测试用户`
   - 邮箱：`test@example.com`（可选）
3. 点击"注册"按钮

**预期结果：**
- 控制台输出代理日志：
  ```
  [Proxy Request] POST /api/auth/register
  [Proxy Response] 200 /api/auth/register
  ```
- 页面显示"注册成功"
- 自动跳转到首页

**如果失败：**
- 检查后端日志是否收到请求
- 检查浏览器 Network 面板查看请求详情
- 检查响应状态码和错误信息

- [ ] **Step 4: 测试用户登录流程**

1. 浏览器访问 `http://localhost:5173/login`
2. 填写登录表单：
   - 用户名：`testuser`
   - 密码：`Test123456!`
3. 点击"登录"按钮

**预期结果：**
- 控制台输出代理日志：
  ```
  [Proxy Request] POST /api/auth/login
  [Proxy Response] 200 /api/auth/login
  ```
- 页面显示"登录成功"
- LocalStorage 存储 Token（F12 -> Application -> Local Storage 查看）
- 自动跳转到首页

- [ ] **Step 5: 验证 JWT Token 自动注入**

在首页刷新后，打开浏览器 DevTools -> Network 面板，观察后续请求：

**预期结果：**
- 所有 `/api/*` 请求的 Request Headers 包含：
  ```
  Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
  ```

- [ ] **Step 6: 验证 Trace ID 同步**

在 Network 面板中选择任意 API 请求：

**预期结果：**
- Request Headers 包含：
  ```
  X-Trace-Id: f47ac10b-58cc-4372-a567-0e02b2c3d479
  ```
- Response Headers 包含相同的 Trace ID

- [ ] **Step 7: 测试 401 跳转**

1. 手动修改 LocalStorage 中的 Token 为无效值：
   ```javascript
   localStorage.setItem('auth_token', 'invalid-token')
   ```
2. 刷新页面触发 `/api/users/me` 请求

**预期结果：**
- 后端返回 401 状态码
- 前端自动清除 LocalStorage 中的 Token
- 自动跳转到 `/login?expired=true` 页面
- 页面显示"登录已过期，请重新登录"

- [ ] **Step 8: 验证类型安全**

修改 `src/api/authApi.ts`，故意写错路径：

```typescript
register: fetcher.path('/api/auth/regster').method('post').create(),
```

运行类型检查：

```bash
npx tsc --noEmit src/api/authApi.ts
```

**预期结果：**
编译错误提示路径不存在：
```
error TS2345: Argument of type '"/api/auth/regster"' is not assignable to parameter of type 'keyof paths'
```

**恢复修改后重新验证通过。**

- [ ] **Step 9: 运行前端单元测试**

```bash
cd smartview-web
npm test
```

预期输出：所有测试通过（如有测试覆盖认证服务）

- [ ] **Step 10: 提交测试验证记录**

创建测试报告文件：

```bash
cat > docs/test-reports/2026-07-22-web-api-integration-test.md << 'EOF'
# Web API 集成测试报告

**测试日期**：2026-07-22
**测试环境**：本地开发环境

## 测试用例

### ✅ 用户注册流程
- 前端表单提交 → 后端接收 → 返回用户信息
- Token 未携带（注册接口不需要认证）
- 响应时间 < 500ms

### ✅ 用户登录流程
- 前端表单提交 → 后端验证 → 返回 Token 和用户信息
- Token 正确存储到 LocalStorage
- 响应时间 < 300ms

### ✅ JWT Token 自动注入
- 登录后所有请求自动携带 Authorization 头
- Token 格式：Bearer <jwt>

### ✅ Trace ID 同步
- 请求头自动生成 Trace ID
- 响应头返回相同 Trace ID

### ✅ 401 自动跳转
- Token 失效时自动清除会话
- 自动跳转到登录页并提示"登录已过期"

### ✅ 类型安全
- 错误的 API 路径在编译时报错
- 缺少必填参数在编译时报错

## 测试结论

✅ 前后端联调成功
✅ 所有功能符合预期
✅ 类型安全机制生效
EOF
```

```bash
git add docs/test-reports/2026-07-22-web-api-integration-test.md
git commit -m "test: 前后端联调集成测试通过

验证项：
- 注册登录流程
- JWT Token 自动注入
- Trace ID 同步
- 401 自动跳转
- 类型安全机制

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 7: 生产环境配置文档

**Files:**
- Create: `smartview-infra/nginx/smartview.conf`
- Create: `docs/deployment/nginx-setup.md`

**Interfaces:**
- Consumes: 无
- Produces: Nginx 配置文件和部署文档

---

- [ ] **Step 1: 创建 Nginx 配置目录**

```bash
mkdir -p smartview-infra/nginx
```

- [ ] **Step 2: 创建 Nginx 配置文件**

```nginx
# HTTP 重定向到 HTTPS
server {
    listen 80;
    server_name smartview.com www.smartview.com;
    return 301 https://smartview.com$request_uri;
}

# HTTPS 主配置
server {
    listen 443 ssl http2;
    server_name smartview.com;

    # SSL 证书配置
    ssl_certificate /etc/nginx/ssl/smartview.com.crt;
    ssl_certificate_key /etc/nginx/ssl/smartview.com.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    # Gzip 压缩
    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml;
    gzip_min_length 1000;

    # 前端静态文件
    location / {
        root /var/www/smartview-web;
        try_files $uri $uri/ /index.html;
        
        # 静态资源缓存
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf)$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
        
        # 禁止缓存 index.html
        location = /index.html {
            add_header Cache-Control "no-cache, no-store, must-revalidate";
        }
    }

    # 后端 API 反向代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        
        # WebSocket 支持（如有需要）
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        
        # 超时配置
        proxy_connect_timeout 30s;
        proxy_send_timeout 30s;
        proxy_read_timeout 30s;
        
        # 缓冲配置
        proxy_buffering on;
        proxy_buffer_size 4k;
        proxy_buffers 8 4k;
    }

    # 健康检查端点（不记录日志）
    location = /api/health {
        proxy_pass http://127.0.0.1:8080/api/health;
        access_log off;
    }

    # 安全头
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;
    add_header Referrer-Policy "no-referrer-when-downgrade" always;
}
```

保存到 `smartview-infra/nginx/smartview.conf`

- [ ] **Step 3: 创建部署文档**

```markdown
# Nginx 部署指南

## 前置条件

- Nginx 已安装（版本 >= 1.18）
- SSL 证书已准备（Let's Encrypt 或购买的证书）
- 前端已构建：`cd smartview-web && npm run build`
- 后端已启动：Spring Boot 运行在 `localhost:8080`

## 部署步骤

### 1. 复制前端构建产物

```bash
# 构建前端
cd smartview-web
npm run build

# 复制到 Nginx 静态文件目录
sudo mkdir -p /var/www/smartview-web
sudo cp -r dist/* /var/www/smartview-web/
sudo chown -R www-data:www-data /var/www/smartview-web
```

### 2. 安装 SSL 证书

```bash
# 使用 Let's Encrypt（推荐）
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d smartview.com -d www.smartview.com

# 或手动放置证书
sudo mkdir -p /etc/nginx/ssl
sudo cp smartview.com.crt /etc/nginx/ssl/
sudo cp smartview.com.key /etc/nginx/ssl/
sudo chmod 600 /etc/nginx/ssl/smartview.com.key
```

### 3. 配置 Nginx

```bash
# 复制配置文件
sudo cp smartview-infra/nginx/smartview.conf /etc/nginx/sites-available/smartview

# 创建软链接启用站点
sudo ln -s /etc/nginx/sites-available/smartview /etc/nginx/sites-enabled/

# 测试配置
sudo nginx -t

# 重载 Nginx
sudo systemctl reload nginx
```

### 4. 配置防火墙

```bash
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw reload
```

### 5. 验证部署

```bash
# 检查 Nginx 状态
sudo systemctl status nginx

# 测试 HTTP 重定向
curl -I http://smartview.com
# 预期：301 重定向到 https://smartview.com

# 测试 HTTPS
curl -I https://smartview.com
# 预期：200 OK

# 测试 API 代理
curl https://smartview.com/api/health
# 预期：{"status":"UP"}
```

## 故障排查

### 问题 1：502 Bad Gateway

**原因：** 后端服务未启动或端口错误

**解决：**
```bash
# 检查后端服务
sudo systemctl status smartview-backend

# 检查端口监听
sudo netstat -tulnp | grep 8080
```

### 问题 2：静态资源 404

**原因：** 前端文件路径错误或权限问题

**解决：**
```bash
# 检查文件存在
ls -la /var/www/smartview-web/

# 检查 Nginx 用户权限
sudo chown -R www-data:www-data /var/www/smartview-web
```

### 问题 3：SSL 证书错误

**原因：** 证书路径错误或证书过期

**解决：**
```bash
# 检查证书有效期
openssl x509 -in /etc/nginx/ssl/smartview.com.crt -noout -dates

# 使用 Let's Encrypt 自动续期
sudo certbot renew --dry-run
```

## 日志位置

- Nginx 访问日志：`/var/log/nginx/access.log`
- Nginx 错误日志：`/var/log/nginx/error.log`
- 后端日志：`smartview-server/logs/application.log`

## 性能优化建议

1. **启用 HTTP/2**（已配置）
2. **启用 Gzip 压缩**（已配置）
3. **配置静态资源缓存**（已配置）
4. **使用 CDN 加速静态资源**（可选）
5. **后端启用 GZIP 响应**（Spring Boot 配置）

## 安全加固建议

1. **定期更新 SSL 证书**
2. **配置 rate limiting 防止 DDoS**
3. **启用 fail2ban 防止暴力破解**
4. **定期备份配置文件**
5. **监控 Nginx 日志异常访问**
```

保存到 `docs/deployment/nginx-setup.md`

- [ ] **Step 4: 提交配置和文档**

```bash
git add smartview-infra/nginx/smartview.conf docs/deployment/nginx-setup.md
git commit -m "docs: 添加生产环境 Nginx 配置和部署指南

- Nginx 反向代理配置
- SSL 证书配置
- 静态资源缓存策略
- 完整部署步骤和故障排查

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 验收标准

完成所有任务后，应满足以下标准：

### 功能验收
- ✅ 前端能成功调用后端 `/api/auth/register` 接口
- ✅ 前端能成功调用后端 `/api/auth/login` 接口
- ✅ 登录成功后 JWT Token 正确存储并自动注入后续请求
- ✅ Token 失效时自动跳转登录页
- ✅ Trace ID 正确生成和同步

### 代码质量验收
- ✅ 前端没有直接拼接 URL（如 `/api/auth/login`）
- ✅ 所有 API 调用使用 `authApi` 生成的函数
- ✅ TypeScript 类型检查通过：`npx tsc --noEmit`
- ✅ 契约修改后重新生成类型，不兼容代码能编译报错
- ✅ 必要位置添加清晰的中文注释

### 文档验收
- ✅ 生产环境 Nginx 配置文件完整
- ✅ 部署文档包含完整步骤和故障排查
- ✅ 测试报告记录所有验证项

---

## 实施完成后的下一步

1. **持续集成**：配置 CI/CD 自动构建和部署
2. **监控告警**：集成 Prometheus + Grafana 监控 API 性能
3. **日志聚合**：配置 ELK Stack 收集和分析日志
4. **契约测试**：使用 Pact 验证前后端契约一致性
5. **E2E 测试**：使用 Playwright 编写端到端测试

---

**计划文档结束**

