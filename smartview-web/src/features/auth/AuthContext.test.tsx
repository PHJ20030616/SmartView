import { useState } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as authService from "./authService";
import { AuthProvider, useAuth } from "./AuthContext";
import {
  clearAuthSession,
  readAuthSession,
  resetMemorySessionForTest,
  saveAuthSession,
} from "./authStorage";
import type { LoginData } from "./authTypes";

vi.mock("./authService", () => ({
  login: vi.fn(),
  register: vi.fn(),
}));

const session = {
  token: "mock-token-smart-user",
  user: {
    id: "1",
    username: "smart-user",
    nickname: "小智",
    status: "ACTIVE" as const,
  },
};

const loginData: LoginData = {
  token: session.token,
  tokenType: "Bearer",
  expiresIn: 7200,
  user: session.user,
};

function AuthProbe() {
  const { isAuthenticated, user } = useAuth();
  return (
    <span>
      {isAuthenticated ? `已登录：${user?.username}` : "未登录"}
    </span>
  );
}

function LoginProbe() {
  const { isAuthenticated, login, user } = useAuth();
  const [persisted, setPersisted] = useState<boolean | null>(null);

  return (
    <>
      <span>
        {isAuthenticated ? `已登录：${user?.username}` : "未登录"}
      </span>
      <span>
        {persisted === null ? "未执行" : persisted ? "已持久化" : "临时登录"}
      </span>
      <button
        type="button"
        onClick={async () => {
          const result = await login(
            { username: "smart-user", password: "secret123" },
            true,
          );
          setPersisted(result.persisted);
        }}
      >
        执行登录
      </button>
    </>
  );
}

function LogoutProbe() {
  const { isAuthenticated, logout } = useAuth();
  return (
    <>
      <span>{isAuthenticated ? "已登录" : "未登录"}</span>
      <button type="button" onClick={logout}>
        退出
      </button>
    </>
  );
}

describe("认证上下文", () => {
  beforeEach(() => {
    localStorage.clear();
    resetMemorySessionForTest();
    clearAuthSession();
    vi.clearAllMocks();
  });

  it("从持久会话初始化为已登录", () => {
    saveAuthSession(session, true);
    resetMemorySessionForTest();

    render(
      <AuthProvider>
        <AuthProbe />
      </AuthProvider>,
    );

    expect(screen.getByText("已登录：smart-user")).toBeInTheDocument();
  });

  it("登录后保存会话并更新用户", async () => {
    vi.mocked(authService.login).mockResolvedValue(loginData);
    const user = userEvent.setup();
    render(
      <AuthProvider>
        <LoginProbe />
      </AuthProvider>,
    );

    await user.click(screen.getByRole("button", { name: "执行登录" }));

    expect(screen.getByText("已登录：smart-user")).toBeInTheDocument();
    expect(screen.getByText("已持久化")).toBeInTheDocument();
    expect(readAuthSession()).toEqual(session);
  });

  it("持久化失败时仍更新为内存登录并报告降级", async () => {
    vi.mocked(authService.login).mockResolvedValue(loginData);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("存储空间不可用");
    });
    const user = userEvent.setup();
    render(
      <AuthProvider>
        <LoginProbe />
      </AuthProvider>,
    );

    await user.click(screen.getByRole("button", { name: "执行登录" }));

    expect(screen.getByText("已登录：smart-user")).toBeInTheDocument();
    expect(screen.getByText("临时登录")).toBeInTheDocument();
    expect(readAuthSession()).toEqual(session);
  });

  it("退出会清理状态和持久化数据", async () => {
    saveAuthSession(session, true);
    const user = userEvent.setup();
    render(
      <AuthProvider>
        <LogoutProbe />
      </AuthProvider>,
    );

    await user.click(screen.getByRole("button", { name: "退出" }));

    expect(screen.getByText("未登录")).toBeInTheDocument();
    expect(readAuthSession()).toBeNull();
  });
});
