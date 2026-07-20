/**
 * HTTP 客户端模块
 *
 * 基于 Axios 封装的 HTTP 客户端，提供：
 * - 请求追踪 ID 管理（生成、存储、同步）
 * - 请求/响应拦截器
 * - 统一的请求配置
 */
import axios from "axios";
import type { AxiosResponse, InternalAxiosRequestConfig } from "axios";

// 追踪 ID 请求头名称
export const TRACE_ID_HEADER = "X-Trace-Id";
// 追踪 ID 在 localStorage 中的存储键
const TRACE_ID_STORAGE_KEY = "smartview.traceId";

// 内存中的追踪 ID 缓存
let memoryTraceId: string | null = null;

/**
 * 追踪 ID 响应类型
 */
type TraceIdResponse = {
  headers?: unknown;
  data?: unknown;
};

/**
 * 创建一个新的追踪 ID（UUID v4 格式）
 *
 * 优先使用 crypto.randomUUID()，降级到 crypto.getRandomValues()，
 * 最后降级到 Math.random()。
 *
 * @returns UUID v4 格式的追踪 ID
 */
export function createTraceId(): string {
  const cryptoApi = typeof globalThis.crypto !== "undefined" ? globalThis.crypto : undefined;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }

  // 手动生成 UUID v4
  const bytes = new Uint8Array(16);
  if (cryptoApi && typeof cryptoApi.getRandomValues === "function") {
    cryptoApi.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }

  // 设置 UUID v4 版本位和变体位
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * 从存储中读取追踪 ID
 *
 * @returns 存储的追踪 ID，不存在则返回 null
 */
function readStoredTraceId(): string | null {
  if (memoryTraceId) {
    return memoryTraceId;
  }

  try {
    return typeof localStorage === "undefined" ? null : localStorage.getItem(TRACE_ID_STORAGE_KEY);
  } catch {
    return null;
  }
}

/**
 * 设置当前追踪 ID
 *
 * 同时更新内存缓存和 localStorage。
 *
 * @param traceId - 要设置的追踪 ID
 */
export function setCurrentTraceId(traceId: string): void {
  memoryTraceId = traceId;
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(TRACE_ID_STORAGE_KEY, traceId);
    }
  } catch {
    // 隐私模式或存储受限时仍保留内存 traceId，保证本次会话可追踪。
  }
}

/**
 * 获取当前追踪 ID
 *
 * 如果不存在则创建一个新的。
 *
 * @returns 当前追踪 ID
 */
export function getCurrentTraceId(): string {
  const existingTraceId = readStoredTraceId();
  if (existingTraceId) {
    return existingTraceId;
  }

  const nextTraceId = createTraceId();
  setCurrentTraceId(nextTraceId);
  return nextTraceId;
}

/**
 * 构造包含追踪 ID 的请求头
 *
 * @returns 包含追踪 ID 的请求头对象
 */
export function buildTraceHeaders(): Record<string, string> {
  return {
    [TRACE_ID_HEADER]: getCurrentTraceId(),
  };
}

/**
 * 规范化请求头值
 *
 * @param value - 请求头值（可能是字符串或字符串数组）
 * @returns 规范化后的字符串，无效则返回 null
 */
function normalizeHeaderValue(value: unknown): string | null {
  if (Array.isArray(value)) {
    const firstValue = value[0];
    return typeof firstValue === "string" && firstValue.trim() ? firstValue : null;
  }

  return typeof value === "string" && value.trim() ? value : null;
}

/**
 * 从响应头中读取指定名称的值
 *
 * @param headers - 响应头对象
 * @param name - 请求头名称
 * @returns 请求头值，不存在则返回 null
 */
function readHeaderValue(headers: unknown, name: string): string | null {
  if (typeof headers !== "object" || headers === null) {
    return null;
  }

  return normalizeHeaderValue((headers as Record<string, unknown>)[name]);
}

/**
 * 从响应体中读取追踪 ID
 *
 * @param data - 响应体数据
 * @returns 追踪 ID，不存在则返回 null
 */
function readTraceIdFromBody(data: unknown): string | null {
  if (typeof data !== "object" || data === null || !("traceId" in data)) {
    return null;
  }

  const traceId = (data as { traceId?: unknown }).traceId;
  return typeof traceId === "string" && traceId.trim() ? traceId : null;
}

/**
 * 从响应中同步追踪 ID
 *
 * 优先从响应头读取，降级到响应体。
 *
 * @param response - 响应对象
 * @returns 同步后的追踪 ID，未找到则返回 null
 */
export function syncTraceIdFromResponse(response: TraceIdResponse): string | null {
  const headerTraceId =
    readHeaderValue(response.headers, TRACE_ID_HEADER) ?? readHeaderValue(response.headers, TRACE_ID_HEADER.toLowerCase());
  const nextTraceId = headerTraceId ?? readTraceIdFromBody(response.data);

  if (nextTraceId) {
    setCurrentTraceId(nextTraceId);
  }

  return nextTraceId;
}

/**
 * 重置追踪 ID（仅用于测试）
 */
export function resetTraceIdForTest(): void {
  memoryTraceId = null;
}

/**
 * HTTP 客户端实例
 *
 * 配置了基础 URL、超时时间和拦截器。
 */
export const httpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api",
  timeout: 15000,
});

// 请求拦截器：自动添加追踪 ID
httpClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers.set(TRACE_ID_HEADER, getCurrentTraceId());
  return config;
});

// 响应拦截器：同步服务端返回的追踪 ID
httpClient.interceptors.response.use(
  (response: AxiosResponse) => {
    syncTraceIdFromResponse(response);
    return response;
  },
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response) {
      syncTraceIdFromResponse(error.response);
    }
    return Promise.reject(error);
  },
);
