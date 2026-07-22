import type {
  LoginData,
  LoginRequest,
  RegisterRequest,
  UserInfo,
} from "./authTypes";

const MOCK_DELAY_MS = 500;

function waitForMockResponse(): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, MOCK_DELAY_MS);
  });
}

function createMockId(): string {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }

  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * Task 2.3 仅模拟前端交互，不保存账号或密码。
 * Task 2.4 接入后端时只需替换本适配器，页面与认证上下文保持不变。
 */
export async function register(request: RegisterRequest): Promise<UserInfo> {
  await waitForMockResponse();

  return {
    id: createMockId(),
    username: request.username,
    nickname: request.nickname,
    email: request.email,
    phone: request.phone,
    status: "ACTIVE",
    createdAt: new Date().toISOString(),
  };
}

export async function login(request: LoginRequest): Promise<LoginData> {
  await waitForMockResponse();

  return {
    token: `mock-token-${createMockId()}`,
    tokenType: "Bearer",
    expiresIn: 7200,
    user: {
      id: createMockId(),
      username: request.username,
      nickname: request.username,
      status: "ACTIVE",
      lastLoginAt: new Date().toISOString(),
      createdAt: new Date().toISOString(),
    },
  };
}
