# Web API 契约集成设计文档

## 文档信息

- **创建日期**：2026-07-22
- **任务编号**：Task 2.4
- **文档版本**：v1.0
- **设计目标**：实现前后端基于 OpenAPI 契约的联调，替换 Mock 服务

---

## 一、背景与目标

### 当前状态

**后端（Spring Boot）：**
- 已实现用户认证接口：`POST /api/auth/register`、`POST /api/auth/login`
- 服务端口：8080
- 响应格式：统一的 `ApiResponse<T>` 包装结构

**前端（React）：**
- 已完成登录注册页面开发
- 当前使用 Mock 服务模拟认证流程
- 已配置 Axios 请求拦截器（JWT Token、Trace ID）
- 已从契约生成 TypeScript 类型定义

**契约文件：**
- `contracts/web-api/openapi.yaml` 包含完整的认证接口定义

### 目标

1. 替换前端 Mock 服务，实现与后端的真实联调
2. 基于 OpenAPI 契约生成类型安全的 API Client
3. 确保契约变更时前端能自动感知并编译报错
4. 解决开发环境跨域问题
5. 为生产环境部署提供 Nginx 配置参考

---

## 二、技术方案

### 方案选型

#### 网络通信方案

**开发环境：Vite 代理**
- 前端请求 `/api/*` 路径
- Vite 开发服务器将请求代理到 `http://localhost:8080`
- 无跨域问题

**生产环境：Nginx 反向代理**
- 前端和后端部署在同一域名下
- Nginx 将 `/api/*` 请求转发到后端服务
- 前端代码无需修改
- 无跨域问题

#### API Client 生成方案

**使用 openapi-typescript-fetch**
- 严格按照 OpenAPI 契约生成 API 调用函数
- 编译时类型检查，防止路径、参数、字段错误
- 契约变更后重新生成，前端自动感知不兼容
- 统一的 API 调用风格

---

## 三、架构设计

### 开发环境架构

```
浏览器 (http://localhost:5173)
    │
    │ 请求 /api/auth/login
    ▼
Vite Dev Server (5173)
    │
    │ proxy: '/api' → 'http://localhost:8080'
    ▼
Spring Boot (8080)
    │
    └─ AuthController: /api/auth/login
```

### 生产环境架构

```
浏览器 (https://smartview.com)
    │
    │ 请求 /api/auth/login
    ▼
Nginx
    ├─ /          → 前端静态文件
    └─ /api/*     → Spring Boot (8080)
```

### 前端模块结构

```
smartview-web/src/
├── api/
│   ├── generated/
│   │   └── schema.ts              # openapi-typescript 生成的类型定义
│   ├── client.ts                  # Fetcher 实例配置（拦截器、baseURL）
│   ├── authApi.ts                 # 认证 API（从契约生成）
│   ├── http.ts                    # Trace ID 管理工具
│   └── request.ts                 # 旧的 Axios 实例（保留用于过渡）
├── features/auth/
│   ├── authService.ts             # 认证服务适配层（调用 authApi）
│   ├── authStorage.ts             # Token 存储管理
│   └── authRedirect.ts            # 认证跳转逻辑
└── pages/login/
    ├── LoginPage.tsx              # 登录页面
    └── RegisterPage.tsx           # 注册页面
```

---

## 四、详细设计

### 4.1 Vite 代理配置

**文件：`smartview-web/vite.config.ts`**

```typescript
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 可选：打印代理日志
        configure: (proxy, _options) => {
          proxy.on('error', (err, _req, _res) => {
            console.log('proxy error', err);
          });
          proxy.on('proxyReq', (proxyReq, req, _res) => {
            console.log('Sending Request:', req.method, req.url);
          });
          proxy.on('proxyRes', (proxyRes, req, _res) => {
            console.log('Received Response:', proxyRes.statusCode, req.url);
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

**说明：**
- `target`：后端服务地址
- `changeOrigin: true`：修改请求头中的 Origin，避免后端拒绝
- `configure`：可选的调试日志，帮助排查代理问题

---

### 4.2 类型化 API Client

#### 4.2.1 Fetcher 实例配置

**文件：`smartview-web/src/api/client.ts`**

```typescript
import { Fetcher } from 'openapi-typescript-fetch';
import type { paths } from './generated/schema';
import { getCurrentTraceId, syncTraceIdFromResponse, TRACE_ID_HEADER } from './http';
import { getAuthToken, clearAuthSession } from '../features/auth/authStorage';
import { redirectToExpiredLogin } from '../features/auth/authRedirect';

/**
 * 类型化的 API Fetcher，严格绑定到 OpenAPI 契约
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
      // 请求拦截器：添加 JWT Token 和 Trace ID
      async onRequest({ request }) {
        // 添加 Trace ID
        request.headers.set(TRACE_ID_HEADER, getCurrentTraceId());

        // 添加 JWT Token
        const token = getAuthToken();
        if (token) {
          request.headers.set('Authorization', `Bearer ${token}`);
        }

        return request;
      },
    },
    {
      // 响应拦截器：同步 Trace ID、处理 401
      async onResponse({ response }) {
        // 从响应中同步 Trace ID
        syncTraceIdFromResponse(response);

        // 处理 401 未授权
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

**设计要点：**
- 绑定到 `paths` 类型，实现编译时类型检查
- 请求拦截器自动注入 JWT Token 和 Trace ID
- 响应拦截器处理 401 跳转和 Trace ID 同步
- 避免并发 401 请求重复触发跳转

---

#### 4.2.2 认证 API

**文件：`smartview-web/src/api/authApi.ts`**

```typescript
import { fetcher } from './client';

/**
 * 认证相关 API
 * 
 * 所有 API 函数均严格按照 OpenAPI 契约生成，
 * 请求路径、方法、参数类型、响应类型均由契约决定。
 */
export const authApi = {
  /**
   * 用户注册
   * 
   * 契约路径：POST /api/auth/register
   * 请求体：RegisterRequest
   * 响应体：ApiResponse<UserInfo>
   */
  register: fetcher.path('/api/auth/register').method('post').create(),

  /**
   * 用户登录
   * 
   * 契约路径：POST /api/auth/login
   * 请求体：LoginRequest
   * 响应体：ApiResponse<LoginData>
   */
  login: fetcher.path('/api/auth/login').method('post').create(),

  /**
   * 获取当前用户信息
   * 
   * 契约路径：GET /api/users/me
   * 需要 JWT Token
   * 响应体：ApiResponse<UserInfo>
   */
  getCurrentUser: fetcher.path('/api/users/me').method('get').create(),
};
```

**类型安全保证：**
```typescript
// ❌ 编译错误：路径不存在
fetcher.path('/api/auth/regster')

// ❌ 编译错误：方法不存在
fetcher.path('/api/auth/login').method('get')

// ❌ 编译错误：缺少必填字段
await authApi.register({ username: 'test' })

// ✅ 正确调用
await authApi.register({ 
  username: 'testuser', 
  password: 'password123',
  nickname: 'Test User'
})
```

---

### 4.3 认证服务适配层

**文件：`smartview-web/src/features/auth/authService.ts`**

```typescript
import { authApi } from '../../api/authApi';
import type { LoginData, LoginRequest, RegisterRequest, UserInfo } from './authTypes';

/**
 * 用户注册
 * 
 * 适配层负责：
 * 1. 调用 authApi.register
 * 2. 解包 ApiResponse 返回 data 字段
 * 3. 统一错误处理（由拦截器处理，此处透传）
 */
export async function register(request: RegisterRequest): Promise<UserInfo> {
  const response = await authApi.register(request);
  
  // Spring Boot 返回 ApiResponse<UserInfo> 结构
  // response.data = { code: "SUCCESS", message: "...", data: UserInfo, traceId: "...", timestamp: "..." }
  return response.data.data;
}

/**
 * 用户登录
 * 
 * 适配层负责：
 * 1. 调用 authApi.login
 * 2. 解包 ApiResponse 返回 data 字段（LoginData 包含 token 和 user）
 */
export async function login(request: LoginRequest): Promise<LoginData> {
  const response = await authApi.login(request);
  return response.data.data;
}

/**
 * 获取当前用户信息
 * 
 * 用于刷新用户信息或验证 Token 有效性
 */
export async function getCurrentUser(): Promise<UserInfo> {
  const response = await authApi.getCurrentUser();
  return response.data.data;
}
```

**为什么需要适配层？**
1. **解包响应**：后端返回 `ApiResponse<T>` 包装，前端只需要 `T`
2. **业务语义**：页面调用 `register()`，而不是 `authApi.register().then(res => res.data.data)`
3. **错误处理**：统一在拦截器处理，适配层无需重复编写
4. **未来扩展**：如需缓存、重试、本地存储等逻辑，在适配层添加

---

### 4.4 响应类型映射

#### 后端响应结构

```java
// Spring Boot: ApiResponse<T>
{
  "code": "SUCCESS",
  "message": "操作成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "id": "123",
      "username": "testuser",
      "nickname": "测试用户",
      "status": "ACTIVE",
      "createdAt": "2026-07-22T10:00:00Z"
    }
  },
  "traceId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "timestamp": "2026-07-22T10:00:00Z"
}
```

#### 前端类型定义

```typescript
// openapi-typescript 生成的类型
type LoginResponse = {
  code: string;
  message: string;
  data: LoginData;
  traceId: string;
  timestamp: string;
}

type LoginData = {
  token: string;
  tokenType: 'Bearer';
  expiresIn: number;
  user: UserInfo;
}
```

#### 适配层处理

```typescript
// authService.ts 返回业务数据
const loginData: LoginData = await login(request);
// loginData = { token, tokenType, expiresIn, user }
```

---

## 五、错误处理设计

### 5.1 HTTP 错误

**拦截器统一处理：**
- `401 Unauthorized`：清除 Token，跳转登录页
- `403 Forbidden`：权限不足提示
- `422 Validation Error`：业务校验失败（由页面处理具体错误信息）
- `500 Internal Server Error`：系统错误提示

### 5.2 业务错误

**后端返回格式：**
```json
{
  "code": "VALIDATION_ERROR",
  "message": "用户名已存在",
  "data": null,
  "traceId": "...",
  "timestamp": "..."
}
```

**前端处理：**
```typescript
try {
  await register(request);
  message.success('注册成功');
} catch (error) {
  if (axios.isAxiosError(error) && error.response?.data?.message) {
    message.error(error.response.data.message);
  } else {
    message.error('注册失败，请稍后重试');
  }
}
```

---

## 六、生产环境部署

### Nginx 配置参考

**文件：`/etc/nginx/sites-available/smartview`**

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

    # 前端静态文件
    location / {
        root /var/www/smartview-web;
        try_files $uri $uri/ /index.html;
        
        # 静态资源缓存
        location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf)$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
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
    }

    # 健康检查端点
    location /api/health {
        proxy_pass http://127.0.0.1:8080/api/health;
        access_log off;
    }
}
```

**配置说明：**
- 前端静态文件部署在 `/var/www/smartview-web`
- `/api/*` 请求反向代理到本地 8080 端口
- 静态资源设置长期缓存
- SPA 路由回退到 `index.html`
- 支持 HTTPS 和 HTTP/2

---

## 七、实施步骤

### 步骤 1：安装依赖
```bash
cd smartview-web
npm install openapi-typescript-fetch
```

### 步骤 2：配置 Vite 代理
修改 `vite.config.ts`，添加 proxy 配置

### 步骤 3：创建 API Client
- 创建 `src/api/client.ts`（Fetcher 配置）
- 创建 `src/api/authApi.ts`（认证 API）

### 步骤 4：改造 authService
- 移除 Mock 实现
- 调用 `authApi.register` 和 `authApi.login`
- 解包 `ApiResponse` 返回 `data` 字段

### 步骤 5：测试前后端联调
- 启动后端：`cd smartview-server && mvn spring-boot:run`
- 启动前端：`cd smartview-web && npm run dev`
- 浏览器访问 `http://localhost:5173`
- 测试注册和登录流程

### 步骤 6：验证类型安全
- 修改契约文件（例如删除 `nickname` 字段）
- 运行 `npm run generate:api`
- 运行 `npm run typecheck`
- 验证 TypeScript 报错

### 步骤 7：编写集成测试
- 使用 `axios-mock-adapter` 或 MSW 模拟后端响应
- 测试认证流程、Token 存储、401 跳转等场景

---

## 八、验收标准

### 功能验收

- [x] 前端能成功调用后端 `/api/auth/register` 接口
- [x] 前端能成功调用后端 `/api/auth/login` 接口
- [x] 登录成功后能正确存储 JWT Token
- [x] 后续请求能自动携带 JWT Token
- [x] Token 失效时能自动跳转登录页
- [x] Trace ID 能正确生成和同步

### 代码质量验收

- [x] 前端没有直接拼接 URL（如 `/api/auth/login`）
- [x] 所有 API 调用使用 `authApi` 生成的函数
- [x] TypeScript 类型检查通过（`npm run typecheck`）
- [x] 单元测试通过（`npm test`）
- [x] 契约修改后重新生成类型，不兼容代码能编译报错

### 性能验收

- [x] 开发环境代理响应时间 < 500ms
- [x] 生产环境 API 响应时间 < 1s
- [x] 前端构建产物大小增量 < 50KB

---

## 九、风险与应对

### 风险 1：后端接口尚未启动

**应对措施：**
- 提供 Docker Compose 一键启动后端依赖（MySQL、Redis、RabbitMQ）
- 编写后端启动脚本，检查依赖服务健康状态
- 提供 Mock Server 作为后端降级方案（基于 Prism 或 MSW）

### 风险 2：契约与后端实现不一致

**应对措施：**
- 后端使用 SpringDoc 自动生成 OpenAPI，确保契约与实现同步
- 编写集成测试验证契约一致性
- 使用 Pact 等契约测试工具

### 风险 3：openapi-typescript-fetch 学习成本

**应对措施：**
- 提供示例代码和最佳实践文档
- 在代码中添加详细的中文注释
- 团队内部进行技术分享

### 风险 4：生产环境 Nginx 配置错误

**应对措施：**
- 提供完整的 Nginx 配置模板
- 在测试环境验证 Nginx 配置
- 编写自动化部署脚本，减少人工配置

---

## 十、后续优化方向

### 优化 1：Request ID 支持

在拦截器中为每个请求生成唯一 Request ID，便于日志关联：
```typescript
request.headers.set('X-Request-Id', crypto.randomUUID());
```

### 优化 2：请求重试机制

对于网络抖动导致的失败，自动重试 3 次：
```typescript
use: [
  {
    async onRequest({ request }) {
      // 添加重试逻辑
    }
  }
]
```

### 优化 3：API 调用日志

在开发环境打印 API 调用日志，便于调试：
```typescript
if (import.meta.env.DEV) {
  console.log('[API]', request.method, request.url);
}
```

### 优化 4：响应缓存

对于不经常变化的数据（如用户信息），实现客户端缓存：
```typescript
const userCache = new Map<string, UserInfo>();
```

### 优化 5：Mock Server

使用 MSW 或 Prism 创建 Mock Server，实现前端独立开发：
```bash
npx @stoplight/prism-cli mock contracts/web-api/openapi.yaml
```

---

## 十一、参考资料

- [OpenAPI Specification 3.0](https://spec.openapis.org/oas/v3.0.3)
- [openapi-typescript 文档](https://github.com/drwpow/openapi-typescript)
- [openapi-typescript-fetch 文档](https://github.com/ajaishankar/openapi-typescript-fetch)
- [Vite 代理配置文档](https://vitejs.dev/config/server-options.html#server-proxy)
- [Nginx 反向代理最佳实践](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)
- [契约优先开发实践](https://swagger.io/resources/articles/adopting-an-api-first-approach/)

---

## 附录：完整文件清单

### 新增文件

```
smartview-web/src/api/
├── client.ts                  # Fetcher 配置和拦截器
└── authApi.ts                 # 认证 API（从契约生成）
```

### 修改文件

```
smartview-web/
├── vite.config.ts             # 添加代理配置
├── package.json               # 添加 openapi-typescript-fetch 依赖
└── src/features/auth/
    └── authService.ts         # 移除 Mock，调用 authApi
```

### 生产环境配置

```
nginx/
└── sites-available/
    └── smartview              # Nginx 配置文件
```

---

**文档结束**
