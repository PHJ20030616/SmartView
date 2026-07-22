import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  AUTH_STORAGE_KEY,
  clearAuthSession,
  getAuthToken,
  readAuthSession,
  resetMemorySessionForTest,
  saveAuthSession,
} from "./authStorage";

const session = {
  token: "mock-token-smart-user",
  user: {
    id: "1",
    username: "smart-user",
    nickname: "小智",
    status: "ACTIVE" as const,
  },
};

describe("认证会话存储", () => {
  beforeEach(() => {
    localStorage.clear();
    resetMemorySessionForTest();
    vi.restoreAllMocks();
  });

  it("持久会话可以在模块内存重置后恢复", () => {
    const result = saveAuthSession(session, true);
    resetMemorySessionForTest();

    expect(result.persisted).toBe(true);
    expect(readAuthSession()).toEqual(session);
    expect(getAuthToken()).toBe(session.token);
  });

  it("非持久会话不会写入 localStorage", () => {
    saveAuthSession(session, false);

    expect(readAuthSession()).toEqual(session);
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it("取消保持登录时会清除之前的持久会话", () => {
    saveAuthSession(session, true);
    saveAuthSession(
      {
        ...session,
        token: "temporary-token",
      },
      false,
    );

    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
    expect(getAuthToken()).toBe("temporary-token");
  });

  it("损坏的本地数据按未登录处理并被清理", () => {
    localStorage.setItem(AUTH_STORAGE_KEY, "{broken");

    expect(readAuthSession()).toBeNull();
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it("缺少必要用户字段的本地数据不会恢复", () => {
    localStorage.setItem(
      AUTH_STORAGE_KEY,
      JSON.stringify({
        token: session.token,
        user: { id: session.user.id, username: session.user.username },
      }),
    );

    expect(readAuthSession()).toBeNull();
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it.each(["", "   "])("空白 Token 的本地会话不会恢复：%j", (token) => {
    localStorage.setItem(
      AUTH_STORAGE_KEY,
      JSON.stringify({
        ...session,
        token,
      }),
    );

    expect(readAuthSession()).toBeNull();
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it("localStorage 写入失败时降级为内存会话", () => {
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("存储空间不可用");
    });

    const result = saveAuthSession(session, true);

    expect(result.persisted).toBe(false);
    expect(readAuthSession()).toEqual(session);
  });

  it("退出登录会同时清理内存和本地会话", () => {
    saveAuthSession(session, true);

    clearAuthSession();

    expect(readAuthSession()).toBeNull();
    expect(localStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });
});
