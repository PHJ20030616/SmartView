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

let expiredRedirectStarted = false;

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
    const isAuthenticatedUnauthorized =
      error.response?.status === 401 &&
      typeof authorization === "string" &&
      authorization.startsWith("Bearer ");

    if (isAuthenticatedUnauthorized && !expiredRedirectStarted) {
      // 登录请求本身没有 Bearer Token，因此不会被误判为 Token 失效。
      // 并发请求可能同时返回 401，模块级锁确保只清理和跳转一次。
      expiredRedirectStarted = true;
      clearAuthSession();
      redirectToExpiredLogin();
    }

    return Promise.reject(error);
  },
);

export function resetExpiredRedirectForTest(): void {
  expiredRedirectStarted = false;
}
