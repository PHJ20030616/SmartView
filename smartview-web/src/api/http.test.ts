import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  buildTraceHeaders,
  createTraceId,
  getCurrentTraceId,
  resetTraceIdForTest,
  syncTraceIdFromResponse,
  TRACE_ID_HEADER,
} from "./http";

describe("traceId 请求封装", () => {
  beforeEach(() => {
    resetTraceIdForTest();
  });

  it("请求头会自动带上 traceId", () => {
    const headers = buildTraceHeaders();

    expect(headers[TRACE_ID_HEADER]).toBeTruthy();
  });

  it("优先使用后端响应头中的 traceId", () => {
    const nextTraceId = syncTraceIdFromResponse({
      headers: { "x-trace-id": "server-trace-id" },
      data: { traceId: "body-trace-id" },
    });

    expect(nextTraceId).toBe("server-trace-id");
    expect(getCurrentTraceId()).toBe("server-trace-id");
  });

  it("没有响应头时使用响应体中的 traceId", () => {
    const nextTraceId = syncTraceIdFromResponse({
      data: { traceId: "body-trace-id" },
    });

    expect(nextTraceId).toBe("body-trace-id");
    expect(getCurrentTraceId()).toBe("body-trace-id");
  });

  it("randomUUID 不可用时仍生成 UUID v4 格式", () => {
    vi.stubGlobal("crypto", {
      getRandomValues(values: Uint8Array) {
        values.fill(1);
        return values;
      },
    });

    const traceId = createTraceId();

    expect(traceId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    vi.unstubAllGlobals();
  });
});
