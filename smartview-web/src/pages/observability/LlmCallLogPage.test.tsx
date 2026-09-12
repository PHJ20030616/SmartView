import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import { fetchLlmCalls, LlmLogError } from "../../features/llmLog";
import LlmCallLogPage from "./LlmCallLogPage";

type LlmCallPage = components["schemas"]["LlmCallPage"];

vi.mock("../../features/llmLog", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../features/llmLog")>();
  return {
    ...actual,
    fetchLlmCalls: vi.fn(),
  };
});

const fetchLlmCallsMock = vi.mocked(fetchLlmCalls);

// antd 会在两个汉字之间自动插入空格（"重试" 渲染为 "重 试"），
// 因此按可见文本匹配按钮时要容忍这个空格，否则断言会因排版细节而失败。
const RETRY_BUTTON = /重\s*试/;

function page(overrides: Partial<LlmCallPage> = {}): LlmCallPage {
  return {
    items: [
      {
        id: "1",
        traceId: "00000000-0000-0000-0000-000000000001",
        scene: "evaluate",
        provider: "deepseek",
        model: "deepseek-v4-flash",
        promptVersion: "p0",
        status: "SUCCESS",
        latencyMs: 1234,
        tokenInput: 100,
        tokenOutput: 200,
        tokenTotal: 300,
        retryAttempt: 0,
        createdAt: "2026-09-12T10:33:19+08:00",
      },
    ],
    page: 1,
    size: 20,
    total: 1,
    stats: {
      totalCalls: 1,
      successCalls: 1,
      successRate: 1,
      p95LatencyMs: 1234,
      totalTokens: 300,
    },
    ...overrides,
  } as LlmCallPage;
}

describe("LLM 调用观测看板", () => {
  // 用 resetAllMocks 而不是 clearAllMocks：clearAllMocks 只清调用记录，
  // 会留下未消费的 mockResolvedValueOnce 队列污染下一个用例（曾导致竞态用例读到上一个用例的响应）。
  afterEach(() => vi.resetAllMocks());

  it("展示调用记录与汇总统计", async () => {
    fetchLlmCallsMock.mockResolvedValue(page());
    render(<LlmCallLogPage />);

    // 场景枚举由前端映射为中文标签
    expect(await screen.findByText("回答评估")).toBeTruthy();
    expect(screen.getByText("1234 ms")).toBeTruthy();
    expect(screen.getByText("100 / 200")).toBeTruthy();
    expect(screen.getByText("deepseek-v4-flash")).toBeTruthy();
    expect(fetchLlmCallsMock).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1, size: 20 }),
      expect.anything(),
    );
  });

  it("403 展示无权限说明且不提供无意义的重试", async () => {
    fetchLlmCallsMock.mockRejectedValue(new LlmLogError("无权访问 LLM 调用观测数据", 403));
    render(<LlmCallLogPage />);

    expect(await screen.findByText("无权访问")).toBeTruthy();
    expect(screen.getByText("无权访问 LLM 调用观测数据")).toBeTruthy();
    // 权限问题重试不会改变结果，按钮存在反而会误导使用者
    expect(screen.queryByRole("button", { name: RETRY_BUTTON })).toBeNull();
  });

  it("普通失败展示加载失败并可重试", async () => {
    fetchLlmCallsMock
      .mockRejectedValueOnce(new LlmLogError("服务内部错误", 500))
      .mockResolvedValueOnce(page());
    render(<LlmCallLogPage />);

    expect(await screen.findByText("加载失败")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: RETRY_BUTTON }));
    expect(await screen.findByText("回答评估")).toBeTruthy();
    expect(fetchLlmCallsMock).toHaveBeenCalledTimes(2);
  });

  it("切换筛选后先发的慢响应不会覆盖后发的结果", async () => {
    // 首个请求悬挂不返回，模拟慢响应；第二个请求立即返回不同统计值。
    let resolveFirst: (value: LlmCallPage) => void = () => {};
    const firstPending = new Promise<LlmCallPage>((resolve) => {
      resolveFirst = resolve;
    });
    fetchLlmCallsMock
      .mockReturnValueOnce(firstPending)
      .mockResolvedValueOnce(page({ total: 2, stats: { ...page().stats, totalCalls: 2 } }));

    render(<LlmCallLogPage />);
    await waitFor(() => expect(fetchLlmCallsMock).toHaveBeenCalledTimes(1));

    // 切换场景触发第二次请求
    await userEvent.click(screen.getByRole("combobox", { name: "调用场景" }));
    await userEvent.click(await screen.findByText("出题"));
    // 分页总数与汇总统计都来自第二次响应（"2" 作为统计值此时唯一，分页页码是 1）
    expect(await screen.findByText("共 2 条")).toBeTruthy();
    expect(screen.getByText("2")).toBeTruthy();

    // 现在才让第一个请求返回：结果必须被丢弃，界面仍显示第二次的值
    resolveFirst(page({ total: 1, stats: { ...page().stats, totalCalls: 1 } }));
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.getByText("共 2 条")).toBeTruthy();
    expect(screen.queryByText("共 1 条")).toBeNull();
  });
});
