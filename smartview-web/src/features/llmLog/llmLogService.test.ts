import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import { listLlmCallsApi } from "./llmLogApi";
import { fetchLlmCalls, LlmLogError, toLlmLogError } from "./llmLogService";

type LlmCallPage = components["schemas"]["LlmCallPage"];

vi.mock("./llmLogApi", () => ({
  listLlmCallsApi: vi.fn(),
}));

const listLlmCallsMock = vi.mocked(listLlmCallsApi);

function page(overrides: Partial<LlmCallPage> = {}): LlmCallPage {
  return {
    items: [],
    page: 1,
    size: 20,
    total: 0,
    stats: {
      totalCalls: 0,
      successCalls: 0,
      successRate: 0,
      p95LatencyMs: 0,
      totalTokens: 0,
    },
    ...overrides,
  } as LlmCallPage;
}

describe("LLM 调用观测服务", () => {
  afterEach(() => vi.clearAllMocks());

  it("fetchLlmCalls 透传查询参数", async () => {
    const result = page();
    listLlmCallsMock.mockResolvedValue(result);

    await expect(fetchLlmCalls({ scene: "evaluate", page: 2, size: 20 })).resolves.toBe(result);
    expect(listLlmCallsMock).toHaveBeenCalledWith(
      { scene: "evaluate", page: 2, size: 20 },
      undefined,
    );
  });

  it("fetchLlmCalls 失败时抛出带后端文案的 LlmLogError", async () => {
    listLlmCallsMock.mockRejectedValue({
      response: { status: 403, data: { message: "无权访问 LLM 调用观测数据" } },
    });

    await expect(fetchLlmCalls({ page: 1, size: 20 })).rejects.toMatchObject({
      name: "LlmLogError",
      message: "无权访问 LLM 调用观测数据",
      status: 403,
    });
  });

  it("toLlmLogError 在拿不到后端文案时回退到兜底中文", () => {
    // 网络中断：没有 response，不能把 axios 的英文默认提示暴露给用户
    const error = toLlmLogError(new Error("Network Error"), "调用记录加载失败，请重试");

    expect(error).toBeInstanceOf(LlmLogError);
    expect(error.message).toBe("调用记录加载失败，请重试");
    expect(error.status).toBeUndefined();
  });

  it("toLlmLogError 对已是 LlmLogError 的输入原样返回", () => {
    const original = new LlmLogError("已处理", 500);

    expect(toLlmLogError(original, "兜底")).toBe(original);
  });
});
