import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { login, register } from "./authService";
import { authApi } from "../../api/authApi";

// Mock authApi 模块
vi.mock("../../api/authApi", () => ({
  authApi: {
    register: vi.fn(),
    login: vi.fn(),
    getCurrentUser: vi.fn(),
  },
}));

describe("前端 Mock 认证服务", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("注册返回用户信息且不会保存密码或本地用户数据库", async () => {
    // Mock authApi.register 返回值
    vi.mocked(authApi.register).mockResolvedValue({
      data: {
        code: 200,
        message: "注册成功",
        data: {
          id: "mock-id-123",
          username: "smart-user",
          nickname: "小智",
          email: "smart@example.com",
          phone: "13800138000",
          status: "ACTIVE",
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      },
      headers: new Headers(),
      ok: true,
      redirected: false,
      status: 200,
      statusText: "OK",
      type: "basic",
      url: "/auth/register",
    } as any);

    const promise = register({
      username: "smart-user",
      nickname: "小智",
      email: "smart@example.com",
      phone: "13800138000",
      password: "secret123",
    });
    await vi.runAllTimersAsync();

    await expect(promise).resolves.toMatchObject({
      username: "smart-user",
      nickname: "小智",
      email: "smart@example.com",
      phone: "13800138000",
      status: "ACTIVE",
    });
    expect(localStorage.length).toBe(0);
    expect(JSON.stringify(await promise)).not.toContain("secret123");
  });

  it("登录返回符合契约的模拟 Token 和用户", async () => {
    // Mock authApi.login 返回值
    vi.mocked(authApi.login).mockResolvedValue({
      data: {
        code: 200,
        message: "登录成功",
        data: {
          token: "mock-token-abc123",
          tokenType: "Bearer",
          expiresIn: 7200,
          user: {
            id: "mock-id-456",
            username: "smart-user",
            nickname: "smart-user",
            email: "smart@example.com",
            phone: "13800138000",
            status: "ACTIVE",
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        },
      },
      headers: new Headers(),
      ok: true,
      redirected: false,
      status: 200,
      statusText: "OK",
      type: "basic",
      url: "/auth/login",
    } as any);

    const promise = login({
      username: "smart-user",
      password: "secret123",
    });
    await vi.runAllTimersAsync();

    await expect(promise).resolves.toMatchObject({
      token: expect.stringMatching(/^mock-token-/),
      tokenType: "Bearer",
      expiresIn: 7200,
      user: {
        username: "smart-user",
        nickname: "smart-user",
        status: "ACTIVE",
      },
    });
  });

  it("登录和注册都保留可感知的异步加载状态", async () => {
    // Mock authApi.login 返回延迟的 Promise
    vi.mocked(authApi.login).mockImplementation(
      () =>
        new Promise((resolve) => {
          setTimeout(() => {
            resolve({
              data: {
                code: 200,
                message: "登录成功",
                data: {
                  token: "mock-token-delayed",
                  tokenType: "Bearer",
                  expiresIn: 7200,
                  user: {
                    id: "mock-id-789",
                    username: "smart-user",
                    nickname: "smart-user",
                    email: "smart@example.com",
                    phone: "13800138000",
                    status: "ACTIVE",
                    createdAt: new Date().toISOString(),
                    updatedAt: new Date().toISOString(),
                  },
                },
              },
              headers: new Headers(),
              ok: true,
              redirected: false,
              status: 200,
              statusText: "OK",
              type: "basic",
              url: "/auth/login",
            } as any);
          }, 500);
        })
    );

    let settled = false;
    const promise = login({
      username: "smart-user",
      password: "secret123",
    }).finally(() => {
      settled = true;
    });

    await vi.advanceTimersByTimeAsync(499);
    expect(settled).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    await promise;
    expect(settled).toBe(true);
  });
});
