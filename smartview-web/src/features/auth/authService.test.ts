import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { login, register } from "./authService";

describe("前端 Mock 认证服务", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("注册返回用户信息且不会保存密码或本地用户数据库", async () => {
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
