import { afterEach, describe, expect, it, vi } from "vitest";

import { request } from "../../api/request";
import { SUBMIT_ANSWER_TIMEOUT_MS, submitAnswerApi } from "./interviewApi";

// 只替换 HTTP 客户端实例：本用例校验的是"提交接口是否单独放宽超时"，
// 不需要真实网络（axios 实例在测试环境会尝试真实请求）
vi.mock("../../api/request", () => ({
  request: { post: vi.fn(), get: vi.fn(), delete: vi.fn() },
}));

const postMock = vi.mocked(request.post);

/** 构造后端统一响应包装（extractData 需要 data 非 null） */
function wrapper(data: unknown) {
  return {
    data: { code: "SUCCESS", message: "操作成功", data, traceId: "t", timestamp: "2026-08-12T00:00:00Z" },
  };
}

describe("面试会话接口", () => {
  afterEach(() => vi.clearAllMocks());

  it("提交回答单独放宽超时，且取值略大于服务端 60s", async () => {
    postMock.mockResolvedValue(
      wrapper({ answerId: "101", evaluation: { score: 80, level: "GOOD" } }) as never,
    );

    await submitAnswerApi("1", "11", "回答", "req-1", 12);

    const config = postMock.mock.calls[0][2] as { timeout?: number };
    // 全局 15s 会让"服务端已推进、前端报超时"的假失败达到 61%，这里必须单独放宽；
    // 又必须大于 Spring/Nginx 的 60s，才能让服务端的中文错误提示先返回给用户
    expect(SUBMIT_ANSWER_TIMEOUT_MS).toBeGreaterThan(60000);
    expect(config.timeout).toBe(SUBMIT_ANSWER_TIMEOUT_MS);
  });
});
