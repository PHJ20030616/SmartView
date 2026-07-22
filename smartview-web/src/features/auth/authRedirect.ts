export type LoginExpiredNavigator = (path: string) => void;

export function getSafeRedirectPath(value: unknown): string {
  // 浏览器会把反斜杠规范化为路径分隔符；同时拒绝控制字符，避免站内回跳被解析为外部地址。
  if (
    typeof value !== "string" ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    value.includes("\\") ||
    /[\u0000-\u001f\u007f]/.test(value)
  ) {
    return "/";
  }

  return value;
}

export function buildLoginPath(
  redirect: string,
  reason?: "expired",
): string {
  const search = new URLSearchParams({
    redirect: getSafeRedirectPath(redirect),
  });
  if (reason) {
    search.set("reason", reason);
  }

  return `/login?${search.toString()}`;
}

export function getCurrentBrowserPath(): string {
  return `${window.location.pathname}${window.location.search}${window.location.hash}`;
}

export function redirectToExpiredLogin(
  navigate: LoginExpiredNavigator = (path) => window.location.replace(path),
): void {
  navigate(buildLoginPath(getCurrentBrowserPath(), "expired"));
}
