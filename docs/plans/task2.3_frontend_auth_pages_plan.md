# Task 2.3 Frontend Authentication Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `smartview-web` 中实现基于 Ant Design 的登录注册页面、可持久化的前端 Mock 认证、Bearer Token 请求拦截、401 失效处理和业务路由保护。

**Architecture:** 使用 OpenAPI 生成类型作为认证数据契约，在 `features/auth` 内隔离存储、Mock 服务、上下文和路由守卫。页面只调用认证服务或上下文；`src/api/request.ts` 作为唯一 Axios 实例统一处理 Trace ID、Token 和 401，Task 2.4 仅替换 `authService` 的 Mock 实现。

**Tech Stack:** React 19、React Router 7、TypeScript 5.9、Ant Design 6、Axios 1.13、Vitest 4、Testing Library、openapi-typescript。

---

## 文件结构

### 新增文件

- `smartview-web/src/api/generated/schema.ts`：由 OpenAPI 命令生成的类型，禁止手工编辑。
- `smartview-web/src/api/request.ts`：项目唯一 Axios 实例和认证拦截器。
- `smartview-web/src/api/request.test.ts`：Token 和 401 拦截器测试。
- `smartview-web/src/features/auth/authTypes.ts`：从生成类型提取认证别名和前端专用表单类型。
- `smartview-web/src/features/auth/authStorage.ts`：内存、本地持久会话读写与降级。
- `smartview-web/src/features/auth/authStorage.test.ts`：会话存储测试。
- `smartview-web/src/features/auth/authRedirect.ts`：站内回跳地址校验和登录 URL 构造。
- `smartview-web/src/features/auth/authRedirect.test.ts`：开放重定向防护测试。
- `smartview-web/src/features/auth/authService.ts`：Task 2.3 Mock 登录注册适配器。
- `smartview-web/src/features/auth/authService.test.ts`：Mock 服务契约测试。
- `smartview-web/src/features/auth/AuthContext.tsx`：认证状态、登录和退出接口。
- `smartview-web/src/features/auth/AuthContext.test.tsx`：状态初始化、持久化和退出测试。
- `smartview-web/src/features/auth/ProtectedRoute.tsx`：未登录业务路由保护。
- `smartview-web/src/features/auth/AnonymousOnlyRoute.tsx`：已登录用户离开认证页面。
- `smartview-web/src/features/auth/index.ts`：认证模块公开出口。
- `smartview-web/src/pages/login/AuthPageLayout.tsx`：登录注册共用品牌分栏布局。
- `smartview-web/src/pages/login/RegisterPage.tsx`：纵向注册表单。
- `smartview-web/src/pages/login/auth-pages.css`：认证页面响应式样式。
- `smartview-web/src/pages/login/LoginPage.test.tsx`：登录页面行为测试。
- `smartview-web/src/pages/login/RegisterPage.test.tsx`：注册顺序、校验和跳转测试。
- `smartview-web/src/app/router.test.tsx`：路由守卫和回跳测试。
- `smartview-web/src/test/setup.ts`：Testing Library 与浏览器 API 测试初始化。

### 修改文件

- `smartview-web/package.json`：增加生成脚本和测试依赖。
- `smartview-web/package-lock.json`：锁定新增依赖。
- `smartview-web/vite.config.ts`：Vitest 切换为 jsdom 并加载 setup。
- `smartview-web/src/api/http.ts`：只保留 Trace ID 辅助逻辑，移除 Axios 实例。
- `smartview-web/src/api/http.test.ts`：继续验证 Trace ID 辅助函数。
- `smartview-web/src/app/App.tsx`：补充认证主题 Token，保留 Ant Design App 上下文。
- `smartview-web/src/app/router.tsx`：注册公开路由、认证提供者和业务路由守卫。
- `smartview-web/src/app/layouts/MainLayout.tsx`：显示当前用户并提供退出操作。
- `smartview-web/src/styles/global.css`：移除旧登录卡片样式，保留业务布局样式。

---

### Task 1: 建立测试环境和 OpenAPI 生成类型

**Files:**
- Modify: `smartview-web/package.json`
- Modify: `smartview-web/package-lock.json`
- Modify: `smartview-web/vite.config.ts`
- Create: `smartview-web/src/test/setup.ts`
- Generate: `smartview-web/src/api/generated/schema.ts`
- Test: `smartview-web/src/api/generated/schema.ts`

- [ ] **Step 1: 安装测试与类型生成依赖**

Run:

```powershell
cd smartview-web
npm install --save-dev @testing-library/jest-dom @testing-library/react @testing-library/user-event axios-mock-adapter jsdom openapi-typescript
```

Expected: 命令退出码为 `0`，`package.json` 和 `package-lock.json` 仅增加上述开发依赖。

- [ ] **Step 2: 增加契约生成脚本**

在 `package.json` 的 `scripts` 中加入：

```json
"generate:api": "openapi-typescript ../contracts/web-api/openapi.yaml -o src/api/generated/schema.ts"
```

Run:

```powershell
npm run generate:api
```

Expected: 生成 `src/api/generated/schema.ts`，其中可搜索到 `RegisterRequest`、`LoginRequest`、`LoginData` 和 `UserInfo`。

- [ ] **Step 3: 配置 Vitest 浏览器测试环境**

将 `vite.config.ts` 的测试配置改为：

```ts
test: {
  environment: "jsdom",
  setupFiles: "./src/test/setup.ts",
},
```

创建 `src/test/setup.ts`：

```ts
import "@testing-library/jest-dom/vitest";

Object.defineProperty(window, "matchMedia", {
  configurable: true,
  value: () => ({
    matches: false,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
});
```

- [ ] **Step 4: 验证生成结果和现有测试**

Run:

```powershell
npm run typecheck
npm test
```

Expected: TypeScript 检查通过，现有 `http.test.ts` 的 4 个用例全部通过。

- [ ] **Step 5: 提交基础设施变更**

```powershell
git add smartview-web/package.json smartview-web/package-lock.json smartview-web/vite.config.ts smartview-web/src/test/setup.ts smartview-web/src/api/generated/schema.ts
git commit -m "chore(web): add auth test and generated types setup"
```

---

### Task 2: 实现认证类型、会话存储和安全回跳

**Files:**
- Create: `smartview-web/src/features/auth/authTypes.ts`
- Create: `smartview-web/src/features/auth/authStorage.ts`
- Create: `smartview-web/src/features/auth/authStorage.test.ts`
- Create: `smartview-web/src/features/auth/authRedirect.ts`
- Create: `smartview-web/src/features/auth/authRedirect.test.ts`

- [ ] **Step 1: 写认证类型和存储失败测试**

`authTypes.ts` 从生成契约提取类型：

```ts
import type { components } from "../../api/generated/schema";

export type RegisterRequest = components["schemas"]["RegisterRequest"];
export type LoginRequest = components["schemas"]["LoginRequest"];
export type LoginData = components["schemas"]["LoginData"];
export type UserInfo = components["schemas"]["UserInfo"];

export type AuthSession = {
  token: string;
  user: UserInfo;
};

export type LoginFormValues = LoginRequest & {
  remember: boolean;
};

export type RegisterFormValues = RegisterRequest & {
  confirmPassword: string;
};
```

`authStorage.test.ts` 至少覆盖：

```ts
it("持久会话可以在模块重新初始化后恢复", () => {
  const result = saveAuthSession(session, true);
  resetMemorySessionForTest();

  expect(result.persisted).toBe(true);
  expect(readAuthSession()).toEqual(session);
});

it("损坏的本地数据按未登录处理并被清理", () => {
  localStorage.setItem(AUTH_STORAGE_KEY, "{broken");

  expect(readAuthSession()).toBeNull();
  expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
});

it("非持久会话不会写入 localStorage", () => {
  saveAuthSession(session, false);

  expect(readAuthSession()).toEqual(session);
  expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
});
```

- [ ] **Step 2: 运行存储测试并确认失败**

Run:

```powershell
npm test -- src/features/auth/authStorage.test.ts
```

Expected: FAIL，提示 `authStorage` 模块或导出函数不存在。

- [ ] **Step 3: 实现会话存储**

`authStorage.ts` 必须导出：

```ts
export const AUTH_STORAGE_KEY = "smartview.auth";

export function readAuthSession(): AuthSession | null;
export function saveAuthSession(
  session: AuthSession,
  persistent: boolean,
): { persisted: boolean };
export function clearAuthSession(): void;
export function getAuthToken(): string | null;
export function resetMemorySessionForTest(): void;
```

实现要求：

- 内存状态优先于 `localStorage`。
- `saveAuthSession(..., false)` 清理旧持久值，防止用户取消“保持登录”后仍被恢复。
- JSON 解析失败、对象缺少 `token`、`user.id`、`user.username` 或 `user.nickname` 时清理持久值。
- `localStorage.setItem` 抛错时保留内存会话并返回 `{ persisted: false }`。
- 对存储降级和损坏数据清理添加必要中文注释。

- [ ] **Step 4: 写回跳安全测试**

`authRedirect.test.ts` 至少覆盖：

```ts
expect(getSafeRedirectPath("/resume?tab=latest#top")).toBe("/resume?tab=latest#top");
expect(getSafeRedirectPath("https://evil.example")).toBe("/");
expect(getSafeRedirectPath("//evil.example/path")).toBe("/");
expect(getSafeRedirectPath("javascript:alert(1)")).toBe("/");
expect(buildLoginPath("/report", "expired")).toBe(
  "/login?redirect=%2Freport&reason=expired",
);
```

- [ ] **Step 5: 运行回跳测试并确认失败**

Run:

```powershell
npm test -- src/features/auth/authRedirect.test.ts
```

Expected: FAIL，提示回跳函数不存在。

- [ ] **Step 6: 实现回跳辅助函数**

`authRedirect.ts` 导出：

```ts
export function getSafeRedirectPath(value: unknown): string {
  if (typeof value !== "string" || !value.startsWith("/") || value.startsWith("//")) {
    return "/";
  }
  return value;
}

export function buildLoginPath(
  redirect: string,
  reason?: "expired",
): string {
  const search = new URLSearchParams({ redirect: getSafeRedirectPath(redirect) });
  if (reason) {
    search.set("reason", reason);
  }
  return `/login?${search.toString()}`;
}
```

同时提供读取当前 `pathname + search + hash` 的浏览器辅助函数，供 401 跳转使用。

- [ ] **Step 7: 运行并提交**

Run:

```powershell
npm test -- src/features/auth/authStorage.test.ts src/features/auth/authRedirect.test.ts
```

Expected: 所有存储和回跳用例通过。

```powershell
git add smartview-web/src/features/auth/authTypes.ts smartview-web/src/features/auth/authStorage.ts smartview-web/src/features/auth/authStorage.test.ts smartview-web/src/features/auth/authRedirect.ts smartview-web/src/features/auth/authRedirect.test.ts
git commit -m "feat(web): add auth session storage and safe redirects"
```

---

### Task 3: 实现 Mock 认证服务和认证上下文

**Files:**
- Create: `smartview-web/src/features/auth/authService.ts`
- Create: `smartview-web/src/features/auth/authService.test.ts`
- Create: `smartview-web/src/features/auth/AuthContext.tsx`
- Create: `smartview-web/src/features/auth/AuthContext.test.tsx`
- Create: `smartview-web/src/features/auth/index.ts`

- [ ] **Step 1: 写 Mock 服务失败测试**

`authService.test.ts` 使用 fake timers 验证：

```ts
it("注册只返回用户信息且不会保存密码", async () => {
  vi.useFakeTimers();
  const promise = register({
    username: "smart-user",
    nickname: "小智",
    password: "secret123",
  });
  await vi.runAllTimersAsync();

  await expect(promise).resolves.toMatchObject({
    username: "smart-user",
    nickname: "小智",
    status: "ACTIVE",
  });
  expect(localStorage.length).toBe(0);
});

it("登录返回符合契约的模拟 Token 和用户", async () => {
  vi.useFakeTimers();
  const promise = login({ username: "smart-user", password: "secret123" });
  await vi.runAllTimersAsync();

  await expect(promise).resolves.toMatchObject({
    tokenType: "Bearer",
    expiresIn: 7200,
    user: { username: "smart-user", status: "ACTIVE" },
  });
});
```

- [ ] **Step 2: 运行 Mock 服务测试并确认失败**

Run:

```powershell
npm test -- src/features/auth/authService.test.ts
```

Expected: FAIL，提示 `register` 和 `login` 不存在。

- [ ] **Step 3: 实现 Mock 服务**

`authService.ts`：

```ts
const MOCK_DELAY_MS = 500;

export async function register(request: RegisterRequest): Promise<UserInfo>;
export async function login(request: LoginRequest): Promise<LoginData>;
```

实现要求：

- 两个方法均等待 `500ms`，用于展示真实加载状态。
- 注册返回用户信息，不写入密码或本地用户数据库。
- 登录接受通过表单校验的任意用户名和密码。
- Token 使用 `mock-token-` 前缀和 `crypto.randomUUID()` 生成。
- `tokenType` 固定为 `Bearer`，`expiresIn` 固定为 `7200`。
- Mock/真实实现替换边界使用必要中文注释说明。

- [ ] **Step 4: 写 AuthProvider 失败测试**

`AuthContext.test.tsx` 覆盖：

```tsx
it("从持久会话初始化为已登录", () => {
  saveAuthSession(session, true);
  render(
    <AuthProvider>
      <AuthProbe />
    </AuthProvider>,
  );

  expect(screen.getByText("已登录：smart-user")).toBeInTheDocument();
});

it("登录后保存会话并更新用户", async () => {
  vi.mocked(authService.login).mockResolvedValue(loginData);
  render(
    <AuthProvider>
      <LoginProbe />
    </AuthProvider>,
  );

  await userEvent.click(screen.getByRole("button", { name: "执行登录" }));
  expect(screen.getByText("已登录：smart-user")).toBeInTheDocument();
  expect(readAuthSession()?.token).toBe(loginData.token);
});

it("退出会清理状态和持久数据", async () => {
  saveAuthSession(session, true);
  render(
    <AuthProvider>
      <LogoutProbe />
    </AuthProvider>,
  );

  await userEvent.click(screen.getByRole("button", { name: "退出" }));
  expect(screen.getByText("未登录")).toBeInTheDocument();
  expect(readAuthSession()).toBeNull();
});
```

- [ ] **Step 5: 实现 AuthProvider**

公开接口：

```ts
type AuthContextValue = {
  session: AuthSession | null;
  user: UserInfo | null;
  token: string | null;
  isAuthenticated: boolean;
  login: (
    credentials: LoginRequest,
    persistent: boolean,
  ) => Promise<{ persisted: boolean }>;
  logout: () => void;
};

export function AuthProvider(props: PropsWithChildren): JSX.Element;
export function useAuth(): AuthContextValue;
```

`login` 调用 `authService.login`，只将 `token` 和 `user` 交给 `authStorage`；`logout` 同时清理内存、本地存储和 React 状态。

- [ ] **Step 6: 运行并提交**

Run:

```powershell
npm test -- src/features/auth/authService.test.ts src/features/auth/AuthContext.test.tsx
```

Expected: Mock 服务和认证上下文全部通过。

```powershell
git add smartview-web/src/features/auth
git commit -m "feat(web): add mock auth service and provider"
```

---

### Task 4: 拆分 Axios 客户端并实现 Token 与 401 拦截

**Files:**
- Modify: `smartview-web/src/api/http.ts`
- Modify: `smartview-web/src/api/http.test.ts`
- Create: `smartview-web/src/api/request.ts`
- Create: `smartview-web/src/api/request.test.ts`

- [ ] **Step 1: 写 Axios 拦截器失败测试**

`request.test.ts` 使用 `axios-mock-adapter`，覆盖：

```ts
it("存在会话时自动添加 Bearer Token", async () => {
  saveAuthSession(session, true);
  mock.onGet("/secure").reply((config) => {
    expect(config.headers?.Authorization).toBe(`Bearer ${session.token}`);
    expect(config.headers?.[TRACE_ID_HEADER]).toBeTruthy();
    return [200, { ok: true }];
  });

  await request.get("/secure");
});

it("无 Token 请求不会添加 Authorization", async () => {
  mock.onPost("/auth/login").reply((config) => {
    expect(config.headers?.Authorization).toBeUndefined();
    return [200, {}];
  });

  await request.post("/auth/login");
});

it("只有携带 Token 的 401 才清理会话并触发失效跳转", async () => {
  saveAuthSession(session, true);
  mock.onGet("/secure").reply(401);

  await expect(request.get("/secure")).rejects.toBeTruthy();
  expect(readAuthSession()).toBeNull();
  expect(redirectToExpiredLogin).toHaveBeenCalledTimes(1);
});
```

另写一个登录请求 `401` 用例，断言不会调用全局失效跳转。

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
npm test -- src/api/request.test.ts
```

Expected: FAIL，提示 `request.ts` 不存在或未注入 Token。

- [ ] **Step 3: 将 Trace ID 与 Axios 实例分离**

从 `http.ts` 移除：

```ts
export const httpClient = axios.create(...);
httpClient.interceptors.request.use(...);
httpClient.interceptors.response.use(...);
```

保留 `TRACE_ID_HEADER`、`createTraceId`、`getCurrentTraceId`、`syncTraceIdFromResponse` 等纯辅助函数，现有 4 个 Trace ID 测试继续通过。

- [ ] **Step 4: 实现唯一请求实例**

`request.ts`：

```ts
export const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api",
  timeout: 15000,
});
```

拦截器要求：

- 请求前设置 `X-Trace-Id`。
- `getAuthToken()` 有值时设置 `Authorization: Bearer <token>`。
- 成功和失败响应都调用 `syncTraceIdFromResponse`。
- 仅当 `error.response?.status === 401` 且原请求头实际包含 Bearer Token 时清理会话并调用 `redirectToExpiredLogin()`。
- 使用模块级锁防止并发 `401` 重复跳转，并导出测试重置函数。
- 对“登录 401 不属于 Token 失效”和“并发 401 锁”添加必要中文注释。

- [ ] **Step 5: 运行并提交**

Run:

```powershell
npm test -- src/api/http.test.ts src/api/request.test.ts
npm run typecheck
```

Expected: Trace ID 原有 4 个用例和新增拦截器用例全部通过，类型检查通过。

```powershell
git add smartview-web/src/api/http.ts smartview-web/src/api/http.test.ts smartview-web/src/api/request.ts smartview-web/src/api/request.test.ts
git commit -m "feat(web): add authenticated axios request client"
```

---

### Task 5: 实现路由守卫和原页面回跳

**Files:**
- Create: `smartview-web/src/features/auth/ProtectedRoute.tsx`
- Create: `smartview-web/src/features/auth/AnonymousOnlyRoute.tsx`
- Modify: `smartview-web/src/features/auth/index.ts`
- Modify: `smartview-web/src/app/router.tsx`
- Create: `smartview-web/src/app/router.test.tsx`

- [ ] **Step 1: 写路由失败测试**

将路由对象提取为可供 `createBrowserRouter` 和测试 `createMemoryRouter` 复用的 `appRoutes`。测试覆盖：

```tsx
it("未登录访问业务页会跳转登录页并保留目标", async () => {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ["/resume?tab=latest"],
  });
  render(<RouterProvider router={router} />);

  expect(await screen.findByRole("heading", { name: "欢迎回来" })).toBeInTheDocument();
  expect(router.state.location.pathname).toBe("/login");
  expect(router.state.location.state).toEqual({
    from: "/resume?tab=latest",
  });
});

it("已登录访问注册页会进入首页", async () => {
  saveAuthSession(session, true);
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ["/register"],
  });
  render(<RouterProvider router={router} />);

  await waitFor(() => expect(router.state.location.pathname).toBe("/"));
});
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
npm test -- src/app/router.test.tsx
```

Expected: FAIL，现有业务路由未受保护且 `/register` 不存在。

- [ ] **Step 3: 实现守卫**

`ProtectedRoute.tsx`：

```tsx
export function ProtectedRoute() {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    const from = `${location.pathname}${location.search}${location.hash}`;
    return <Navigate to="/login" replace state={{ from }} />;
  }

  return <Outlet />;
}
```

`AnonymousOnlyRoute.tsx` 在已登录时读取安全回跳地址，默认跳转 `/`；未登录时渲染 `Outlet`。

- [ ] **Step 4: 重构路由层级**

路由结构：

```tsx
export const appRoutes: RouteObject[] = [
  {
    element: <AuthProvider><Outlet /></AuthProvider>,
    children: [
      {
        element: <AnonymousOnlyRoute />,
        children: [
          { path: "/login", element: <LoginPage /> },
          { path: "/register", element: <RegisterPage /> },
        ],
      },
      {
        element: <ProtectedRoute />,
        children: [
          {
            path: "/",
            element: <MainLayout />,
            children: [
              { index: true, element: <HomePage /> },
              { path: "resume", element: <ResumePage /> },
              { path: "interview", element: <InterviewPage /> },
              { path: "report", element: <ReportPage /> },
            ],
          },
        ],
      },
      { path: "*", element: <Navigate to="/" replace /> },
    ],
  },
];
```

- [ ] **Step 5: 运行并提交**

Run:

```powershell
npm test -- src/app/router.test.tsx
```

Expected: 未登录保护、原地址保留、已登录公开页跳转和未知路由用例全部通过。

```powershell
git add smartview-web/src/features/auth/ProtectedRoute.tsx smartview-web/src/features/auth/AnonymousOnlyRoute.tsx smartview-web/src/features/auth/index.ts smartview-web/src/app/router.tsx smartview-web/src/app/router.test.tsx
git commit -m "feat(web): protect business routes with auth guards"
```

---

### Task 6: 使用 Ant Design 实现登录和纵向注册页面

**Files:**
- Create: `smartview-web/src/pages/login/AuthPageLayout.tsx`
- Modify: `smartview-web/src/pages/login/LoginPage.tsx`
- Create: `smartview-web/src/pages/login/RegisterPage.tsx`
- Create: `smartview-web/src/pages/login/auth-pages.css`
- Create: `smartview-web/src/pages/login/LoginPage.test.tsx`
- Create: `smartview-web/src/pages/login/RegisterPage.test.tsx`
- Modify: `smartview-web/src/styles/global.css`
- Modify: `smartview-web/src/app/App.tsx`

- [ ] **Step 1: 写注册页面失败测试**

`RegisterPage.test.tsx` 覆盖字段顺序：

```tsx
const labels = [
  "用户名",
  "昵称",
  "邮箱（选填）",
  "手机号（选填）",
  "密码",
  "确认密码",
];
const inputs = labels.map((label) => screen.getByLabelText(label));

for (let index = 1; index < inputs.length; index += 1) {
  expect(
    inputs[index - 1].compareDocumentPosition(inputs[index]) &
      Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
}
```

同时覆盖：

- 空提交出现用户名、昵称、密码和确认密码中文错误。
- 用户名小于 3 个字符时阻止提交。
- 非法邮箱和手机号显示对应错误。
- 两次密码不一致显示“两次输入的密码不一致”。
- 成功注册后出现中文成功消息，跳转 `/login` 并预填用户名。

- [ ] **Step 2: 写登录页面失败测试**

`LoginPage.test.tsx` 覆盖：

- 空提交显示用户名和密码错误。
- 默认选中“保持登录状态”。
- 提交时按钮进入 loading，防止重复提交。
- 登录成功后优先返回安全原目标地址。
- 持久化失败时展示“当前为临时登录，刷新后需要重新登录”。
- `reason=expired` 时展示“登录状态已失效，请重新登录”。

- [ ] **Step 3: 运行页面测试并确认失败**

Run:

```powershell
npm test -- src/pages/login/LoginPage.test.tsx src/pages/login/RegisterPage.test.tsx
```

Expected: FAIL，注册页和新交互尚未实现。

- [ ] **Step 4: 实现共用品牌布局**

`AuthPageLayout.tsx` 使用：

- 左侧 SmartView 品牌名、主标题和三条能力说明。
- 右侧接收 `title`、`description` 和 `children`。
- 移动端显示紧凑品牌头，隐藏完整侧栏。
- 图标使用 `TeamOutlined`、`BarChartOutlined`、`SafetyCertificateOutlined`。

必要属性：

```ts
type AuthPageLayoutProps = PropsWithChildren<{
  title: string;
  description: string;
}>;
```

- [ ] **Step 5: 实现注册页**

使用 `Form<RegisterFormValues> layout="vertical" requiredMark={false} scrollToFirstError`，字段严格按已确认顺序书写。

提交逻辑：

```ts
const handleFinish = async (values: RegisterFormValues) => {
  setSubmitting(true);
  try {
    const { confirmPassword: _confirmPassword, ...request } = values;
    await authService.register(request);
    message.success("注册成功，请登录");
    navigate("/login", {
      replace: true,
      state: { registeredUsername: values.username },
    });
  } catch {
    message.error("注册失败，请稍后重试");
  } finally {
    setSubmitting(false);
  }
};
```

确认密码使用 `dependencies={["password"]}` 和自定义 validator；邮箱、手机号、长度规则与 OpenAPI 契约一致。

- [ ] **Step 6: 实现登录页**

登录页读取：

- `location.state?.registeredUsername` 用于注册后预填。
- `location.state?.from` 或查询参数 `redirect` 作为回跳地址。
- 查询参数 `reason=expired` 用于失效提示。

提交逻辑：

```ts
const result = await login(
  { username: values.username, password: values.password },
  values.remember,
);
if (!result.persisted && values.remember) {
  message.warning("当前为临时登录，刷新后需要重新登录");
} else {
  message.success("登录成功");
}
navigate(redirectPath, { replace: true });
```

错误统一显示“登录失败，请检查账号和密码后重试”，不展示底层异常。

- [ ] **Step 7: 实现样式和主题**

`auth-pages.css` 复刻已确认原型：

- 桌面 `40% / 60%` 品牌分栏。
- 容器最大宽度 `1080px`，圆角不超过 `8px`。
- 注册字段单列，输入控件稳定高度。
- `820px` 以下切换为移动单列。
- 不使用卡片嵌套、渐变球、手写 SVG 或伪输入框。

`App.tsx` 的主题补充：

```ts
token: {
  borderRadius: 6,
  colorPrimary: "#0f766e",
  colorText: "#172033",
  colorTextSecondary: "#667085",
  controlHeight: 42,
}
```

从 `global.css` 删除旧 `.login-page` 和 `.login-panel`。

- [ ] **Step 8: 运行并提交**

Run:

```powershell
npm test -- src/pages/login/LoginPage.test.tsx src/pages/login/RegisterPage.test.tsx
npm run typecheck
```

Expected: 页面测试和类型检查全部通过。

```powershell
git add smartview-web/src/pages/login smartview-web/src/styles/global.css smartview-web/src/app/App.tsx
git commit -m "feat(web): build Ant Design login and register pages"
```

---

### Task 7: 更新主布局用户状态和退出登录

**Files:**
- Modify: `smartview-web/src/app/layouts/MainLayout.tsx`
- Create or Modify: `smartview-web/src/app/layouts/MainLayout.test.tsx`

- [ ] **Step 1: 写退出登录失败测试**

测试：

```tsx
it("显示当前用户并在退出后进入登录页", async () => {
  saveAuthSession(session, true);
  const router = createMemoryRouter(appRoutes, { initialEntries: ["/"] });
  render(<RouterProvider router={router} />);

  expect(await screen.findByText(session.user.nickname)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "退出登录" }));

  expect(readAuthSession()).toBeNull();
  await waitFor(() => expect(router.state.location.pathname).toBe("/login"));
});
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
npm test -- src/app/layouts/MainLayout.test.tsx
```

Expected: FAIL，现有顶部栏仍显示“登录”。

- [ ] **Step 3: 实现用户信息和退出操作**

`MainLayout.tsx`：

- 使用 `useAuth()` 读取 `user` 和 `logout`。
- 顶栏显示用户昵称。
- 使用 `LogoutOutlined` 图标按钮，`aria-label="退出登录"`。
- 点击后先 `logout()`，再 `navigate("/login", { replace: true })`。
- 删除已登录状态下无意义的“登录”按钮。

- [ ] **Step 4: 运行并提交**

Run:

```powershell
npm test -- src/app/layouts/MainLayout.test.tsx src/app/router.test.tsx
```

Expected: 退出和路由保护测试全部通过。

```powershell
git add smartview-web/src/app/layouts/MainLayout.tsx smartview-web/src/app/layouts/MainLayout.test.tsx
git commit -m "feat(web): add authenticated user header and logout"
```

---

### Task 8: 完整验证、独立审查、修复与双远程同步

**Files:**
- Verify: `smartview-web/src/**`
- Create if review finds issues: `docs/errors/task2.3_frontend_auth_pages_plan_errors.md`
- Modify only if review finds issues: 对应问题文件和测试

- [ ] **Step 1: 执行完整自动化验证**

Run:

```powershell
cd smartview-web
npm run generate:api
npm test
npm run typecheck
npm run build
```

Expected:

- OpenAPI 生成命令成功且 `git diff` 不产生非预期生成漂移。
- 所有 Vitest 用例通过。
- TypeScript 零错误。
- Vite 生产构建成功。

- [ ] **Step 2: 启动开发服务器**

Run:

```powershell
npm run dev -- --host 127.0.0.1
```

Expected: Vite 输出可访问的本地 URL；如果 `5173` 被占用，使用 Vite 自动选择的端口。

- [ ] **Step 3: 浏览器验证核心流程**

使用桌面视口和 `390x844` 移动视口逐项检查：

1. 未登录直接访问 `/resume`，进入登录页。
2. 登录页提交空表单，中文校验正确。
3. 进入注册页，六个字段竖向排列且无重叠。
4. 注册成功后进入登录页并预填用户名。
5. 登录成功后返回原 `/resume`。
6. 刷新页面后仍保持登录。
7. 退出登录后无法直接访问业务页。
8. 模拟携带 Token 的 `401`，会话被清理并显示失效提示。
9. 桌面和移动页面没有横向溢出、文本遮挡或空白内容。
10. 浏览器控制台没有错误。

- [ ] **Step 4: 调用全新子 Agent 审查**

审查范围必须包含：

- Token 是否可能泄漏或写入错误位置。
- 登录 `401` 是否会被误判为 Token 失效。
- 原地址回跳是否存在开放重定向。
- 刷新初始化是否出现错误跳转。
- 注册字段是否符合契约且保持纵向顺序。
- 中文文案和必要中文注释是否齐全。
- 测试是否覆盖验收标准和关键边界。

- [ ] **Step 5: 按审查结果执行修复流程**

若审查无问题，记录“未发现阻断问题”，继续下一步。

若发现问题：

1. 创建 `docs/errors/task2.3_frontend_auth_pages_plan_errors.md`。
2. 写明问题、根因、修复文件、回归测试和验收标准。
3. 用户确认修复计划后再修改代码。
4. 重新运行相关测试和完整验证。
5. 再调用新的子 Agent 复审；新问题追加到同一错误计划。

- [ ] **Step 6: 检查提交范围和敏感文件**

Run:

```powershell
git status --short
git diff --check
git check-ignore -v smartview-web/.env
git remote -v
```

Expected:

- 不暂存用户已有的后端改动和 `.gitignore` 改动。
- 无空白错误。
- `.env` 被忽略。
- 远程配置中包含 GitHub 与 Gitee；缺失时按项目规则补齐 remote。

- [ ] **Step 7: 提交最终验证或修复**

仅暂存本任务产生的前端、计划和错误修复文件：

```powershell
git add smartview-web docs/plans/task2.3_frontend_auth_pages_plan.md
git add docs/errors/task2.3_frontend_auth_pages_plan_errors.md
git commit -m "feat(web): complete frontend authentication pages"
```

如果错误计划不存在，不执行对应 `git add`。若前述任务提交已包含全部代码且没有修复，只提交尚未提交的计划或验证文档。

- [ ] **Step 8: 推送两个远程仓库**

先根据 `git remote -v` 确定或补充：

- `git@github.com:PHJ20030616/SmartView.git`
- `https://gitee.com/phj20030616/smart-view.git`

Run:

```powershell
git push <github-remote> HEAD:master
git push <gitee-remote> HEAD:master
```

Expected: 两个推送命令均退出码为 `0`，远程 `master` 指向相同最终提交。

- [ ] **Step 9: 输出验收报告**

最终报告必须包含：

- 修改的核心文件。
- 单元测试、组件测试、路由测试和浏览器验证范围。
- 每条测试命令、通过数、失败数。
- TypeScript 和生产构建结果。
- 独立审查结论及是否创建错误计划。
- Git 提交哈希。
- GitHub 和 Gitee 推送结果。
