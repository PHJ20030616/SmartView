import type { AuthSession, UserInfo } from "./authTypes";

export const AUTH_STORAGE_KEY = "smartview.auth";

let memorySession: AuthSession | null = null;

function isUserInfo(value: unknown): value is UserInfo {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const user = value as Partial<UserInfo>;
  return (
    typeof user.id === "string" &&
    typeof user.username === "string" &&
    typeof user.nickname === "string"
  );
}

function isAuthSession(value: unknown): value is AuthSession {
  if (typeof value !== "object" || value === null) {
    return false;
  }

  const session = value as Partial<AuthSession>;
  return (
    typeof session.token === "string" &&
    session.token.trim().length > 0 &&
    isUserInfo(session.user)
  );
}

function removePersistedSession(): void {
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.removeItem(AUTH_STORAGE_KEY);
    }
  } catch {
    // 存储受限时无需阻断退出流程，内存状态仍会被可靠清理。
  }
}

export function readAuthSession(): AuthSession | null {
  if (memorySession) {
    return memorySession;
  }

  try {
    const storedValue =
      typeof localStorage === "undefined"
        ? null
        : localStorage.getItem(AUTH_STORAGE_KEY);
    if (!storedValue) {
      return null;
    }

    const parsedValue: unknown = JSON.parse(storedValue);
    if (!isAuthSession(parsedValue)) {
      // 本地数据可能来自旧版本或被意外修改，按未登录处理可避免恢复出不完整会话。
      removePersistedSession();
      return null;
    }

    memorySession = parsedValue;
    return memorySession;
  } catch {
    // JSON 损坏或浏览器禁止读取存储时，清理可访问的旧值并回退为未登录状态。
    removePersistedSession();
    return null;
  }
}

export function saveAuthSession(
  session: AuthSession,
  persistent: boolean,
): { persisted: boolean } {
  memorySession = session;

  if (!persistent) {
    // 用户取消“保持登录状态”时必须移除旧持久值，避免刷新后恢复上一段会话。
    removePersistedSession();
    return { persisted: false };
  }

  try {
    localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(session));
    return { persisted: true };
  } catch {
    // 隐私模式或配额不足时保留内存会话，使本次标签页仍可继续使用。
    return { persisted: false };
  }
}

export function clearAuthSession(): void {
  memorySession = null;
  removePersistedSession();
}

export function getAuthToken(): string | null {
  return readAuthSession()?.token ?? null;
}

export function resetMemorySessionForTest(): void {
  memorySession = null;
}
