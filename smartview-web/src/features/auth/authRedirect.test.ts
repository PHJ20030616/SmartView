import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  buildLoginPath,
  getCurrentBrowserPath,
  getSafeRedirectPath,
  redirectToExpiredLogin,
} from "./authRedirect";

describe("认证回跳地址", () => {
  beforeEach(() => {
    window.history.replaceState({}, "", "/");
    vi.restoreAllMocks();
  });

  it("保留安全的站内绝对路径及查询参数和锚点", () => {
    expect(getSafeRedirectPath("/resume?tab=latest#top")).toBe(
      "/resume?tab=latest#top",
    );
  });

  it.each([
    "https://evil.example",
    "//evil.example/path",
    "/\\evil.example/path",
    "/resume\\evil.example",
    "/resume\n/evil.example",
    "javascript:alert(1)",
    "resume",
    "",
  ])("拒绝非站内绝对回跳地址：%s", (value) => {
    expect(getSafeRedirectPath(value)).toBe("/");
  });

  it("非字符串回跳值使用首页", () => {
    expect(getSafeRedirectPath(null)).toBe("/");
  });

  it("构造携带原地址和失效原因的登录路径", () => {
    expect(buildLoginPath("/report", "expired")).toBe(
      "/login?redirect=%2Freport&reason=expired",
    );
  });

  it("读取当前完整浏览器路径", () => {
    window.history.replaceState({}, "", "/resume?tab=latest#top");

    expect(getCurrentBrowserPath()).toBe("/resume?tab=latest#top");
  });

  it("Token 失效时替换到登录页面并保留当前地址", () => {
    window.history.replaceState({}, "", "/interview?mode=mock");
    const replaceSpy = vi.fn();

    redirectToExpiredLogin(replaceSpy);

    expect(replaceSpy).toHaveBeenCalledWith(
      "/login?redirect=%2Finterview%3Fmode%3Dmock&reason=expired",
    );
  });
});
