import { AxiosHeaders } from "axios";
import AxiosMockAdapter from "axios-mock-adapter";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { redirectToExpiredLogin } from "../features/auth/authRedirect";
import {
  readAuthSession,
  resetMemorySessionForTest,
  saveAuthSession,
} from "../features/auth/authStorage";
import { resetTraceIdForTest, TRACE_ID_HEADER } from "./http";
import { request, resetExpiredRedirectForTest } from "./request";

vi.mock("../features/auth/authRedirect", () => ({
  redirectToExpiredLogin: vi.fn(),
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

describe("认证请求客户端", () => {
  let mock: AxiosMockAdapter;

  beforeEach(() => {
    localStorage.clear();
    resetMemorySessionForTest();
    resetTraceIdForTest();
    resetExpiredRedirectForTest();
    vi.clearAllMocks();
    mock = new AxiosMockAdapter(request);
  });

  afterEach(() => {
    mock.restore();
  });

  it("存在会话时自动添加 Bearer Token 和 Trace ID", async () => {
    saveAuthSession(session, true);
    mock.onGet("/secure").reply((config) => {
      const headers = AxiosHeaders.from(config.headers as AxiosHeaders);
      expect(headers.get("Authorization")).toBe(`Bearer ${session.token}`);
      expect(headers.get(TRACE_ID_HEADER)).toBeTruthy();
      return [200, { ok: true }];
    });

    await request.get("/secure");
  });

  it("无 Token 请求不会添加 Authorization", async () => {
    mock.onPost("/auth/login").reply((config) => {
      const headers = AxiosHeaders.from(config.headers as AxiosHeaders);
      expect(headers.get("Authorization")).toBeUndefined();
      return [200, {}];
    });

    await request.post("/auth/login");
  });

  it("未携带 Token 的 401 不会触发全局失效跳转", async () => {
    mock.onPost("/auth/login").reply(401);

    await expect(request.post("/auth/login")).rejects.toBeTruthy();

    expect(redirectToExpiredLogin).not.toHaveBeenCalled();
  });

  it("携带 Token 的 401 会清理会话并触发失效跳转", async () => {
    saveAuthSession(session, true);
    mock.onGet("/secure").reply(401);

    await expect(request.get("/secure")).rejects.toBeTruthy();

    expect(readAuthSession()).toBeNull();
    expect(redirectToExpiredLogin).toHaveBeenCalledTimes(1);
  });

  it("并发 401 只触发一次失效跳转", async () => {
    saveAuthSession(session, true);
    mock.onGet("/secure/one").reply(401);
    mock.onGet("/secure/two").reply(401);

    await Promise.allSettled([
      request.get("/secure/one"),
      request.get("/secure/two"),
    ]);

    expect(redirectToExpiredLogin).toHaveBeenCalledTimes(1);
  });

  it("旧会话请求延迟返回 401 时不会清除新会话", async () => {
    let releaseOldRequest!: (response: [number, object]) => void;
    let markRequestStarted!: () => void;
    const requestStarted = new Promise<void>((resolve) => {
      markRequestStarted = resolve;
    });
    const oldResponse = new Promise<[number, object]>((resolve) => {
      releaseOldRequest = resolve;
    });
    const nextSession = {
      ...session,
      token: "mock-token-new-session",
    };

    saveAuthSession(session, true);
    mock.onGet("/secure/slow").reply(() => {
      markRequestStarted();
      return oldResponse;
    });

    const pendingRequest = request.get("/secure/slow");
    await requestStarted;
    saveAuthSession(nextSession, true);
    releaseOldRequest([401, {}]);

    await expect(pendingRequest).rejects.toBeTruthy();
    expect(readAuthSession()).toEqual(nextSession);
    expect(redirectToExpiredLogin).not.toHaveBeenCalled();
  });
});
