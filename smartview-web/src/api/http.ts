import axios from "axios";
import type { AxiosResponse, InternalAxiosRequestConfig } from "axios";

export const TRACE_ID_HEADER = "X-Trace-Id";
const TRACE_ID_STORAGE_KEY = "smartview.traceId";

let memoryTraceId: string | null = null;

type TraceIdResponse = {
  headers?: unknown;
  data?: unknown;
};

export function createTraceId(): string {
  const cryptoApi = typeof globalThis.crypto !== "undefined" ? globalThis.crypto : undefined;
  if (cryptoApi && typeof cryptoApi.randomUUID === "function") {
    return cryptoApi.randomUUID();
  }

  const bytes = new Uint8Array(16);
  if (cryptoApi && typeof cryptoApi.getRandomValues === "function") {
    cryptoApi.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }

  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

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

export function getCurrentTraceId(): string {
  const existingTraceId = readStoredTraceId();
  if (existingTraceId) {
    return existingTraceId;
  }

  const nextTraceId = createTraceId();
  setCurrentTraceId(nextTraceId);
  return nextTraceId;
}

export function buildTraceHeaders(): Record<string, string> {
  return {
    [TRACE_ID_HEADER]: getCurrentTraceId(),
  };
}

function normalizeHeaderValue(value: unknown): string | null {
  if (Array.isArray(value)) {
    const firstValue = value[0];
    return typeof firstValue === "string" && firstValue.trim() ? firstValue : null;
  }

  return typeof value === "string" && value.trim() ? value : null;
}

function readHeaderValue(headers: unknown, name: string): string | null {
  if (typeof headers !== "object" || headers === null) {
    return null;
  }

  return normalizeHeaderValue((headers as Record<string, unknown>)[name]);
}

function readTraceIdFromBody(data: unknown): string | null {
  if (typeof data !== "object" || data === null || !("traceId" in data)) {
    return null;
  }

  const traceId = (data as { traceId?: unknown }).traceId;
  return typeof traceId === "string" && traceId.trim() ? traceId : null;
}

export function syncTraceIdFromResponse(response: TraceIdResponse): string | null {
  const headerTraceId =
    readHeaderValue(response.headers, TRACE_ID_HEADER) ?? readHeaderValue(response.headers, TRACE_ID_HEADER.toLowerCase());
  const nextTraceId = headerTraceId ?? readTraceIdFromBody(response.data);

  if (nextTraceId) {
    setCurrentTraceId(nextTraceId);
  }

  return nextTraceId;
}

export function resetTraceIdForTest(): void {
  memoryTraceId = null;
}

export const httpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "/api",
  timeout: 15000,
});

httpClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  config.headers.set(TRACE_ID_HEADER, getCurrentTraceId());
  return config;
});

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
