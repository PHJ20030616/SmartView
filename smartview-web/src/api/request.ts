import axios, {
  AxiosHeaders,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from "axios";

import { redirectToExpiredLogin } from "../features/auth/authRedirect";
import {
  clearAuthSession,
  getAuthToken,
} from "../features/auth/authStorage";
import {
  getCurrentTraceId,
  syncTraceIdFromResponse,
  TRACE_ID_HEADER,
} from "./http";

let expiredRedirectToken: string | null = null;

export const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api",
  timeout: 15000,
});

request.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers.set(TRACE_ID_HEADER, getCurrentTraceId());

  const token = getAuthToken();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }

  return config;
});

request.interceptors.response.use(
  (response: AxiosResponse) => {
    syncTraceIdFromResponse(response);
    return response;
  },
  (error: unknown) => {
    if (!axios.isAxiosError(error)) {
      return Promise.reject(error);
    }

    if (error.response) {
      syncTraceIdFromResponse(error.response);
    }

    const headers = new AxiosHeaders(error.config?.headers);
    const authorization = headers.get("Authorization");
    const failedToken =
      typeof authorization === "string"
        ? /^Bearer\s+(.+)$/i.exec(authorization)?.[1] ?? null
        : null;
    const isCurrentSessionUnauthorized =
      error.response?.status === 401 &&
      failedToken !== null &&
      failedToken === getAuthToken();

    if (
      isCurrentSessionUnauthorized &&
      failedToken !== expiredRedirectToken
    ) {
      // 只处理当前会话发出的 401，避免旧请求延迟返回后误清除用户刚建立的新会话。
      // 首次处理会立即清空当前 Token，并发返回的相同 401 因此不会重复触发跳转。
      expiredRedirectToken = failedToken;
      clearAuthSession();
      redirectToExpiredLogin();
    }

    return Promise.reject(error);
  },
);

export function resetExpiredRedirectForTest(): void {
  expiredRedirectToken = null;
}
