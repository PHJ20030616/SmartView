export type LoginExpiredNavigator = (path: string) => void;

export function getSafeRedirectPath(value: unknown): string {
  if (
    typeof value !== "string" ||
    !value.startsWith("/") ||
    value.startsWith("//")
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
