import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import {
  getReportApi,
  getReportBySessionApi,
  retryReportApi,
} from "./reportApi";
import {
  fetchReport,
  fetchReportBySession,
  ReportError,
  retryReport,
  toReportError,
  waitForReport,
} from "./reportService";

type InterviewReport = components["schemas"]["InterviewReport"];

vi.mock("./reportApi", () => ({
  getReportApi: vi.fn(),
  getReportBySessionApi: vi.fn(),
  retryReportApi: vi.fn(),
}));

const getReportMock = vi.mocked(getReportApi);
const getReportBySessionMock = vi.mocked(getReportBySessionApi);
const retryReportMock = vi.mocked(retryReportApi);

function report(overrides: Partial<InterviewReport> = {}): InterviewReport {
  return {
    id: "88",
    sessionId: "66",
    userId: "7",
    status: "SUCCESS",
    ...overrides,
  } as InterviewReport;
}

describe("报告服务", () => {
  afterEach(() => vi.clearAllMocks());

  it("fetchReportBySession 透传 sessionId", async () => {
    const r = report();
    getReportBySessionMock.mockResolvedValue(r);
    await expect(fetchReportBySession("66")).resolves.toBe(r);
    expect(getReportBySessionMock).toHaveBeenCalledWith("66", undefined);
  });

  it("fetchReport 透传 reportId", async () => {
    const r = report();
    getReportMock.mockResolvedValue(r);
    await expect(fetchReport("88")).resolves.toBe(r);
    expect(getReportMock).toHaveBeenCalledWith("88", undefined);
  });

  it("retryReport 调用重试端点", async () => {
    const r = report({ status: "GENERATING" });
    retryReportMock.mockResolvedValue(r);
    await expect(retryReport("88")).resolves.toBe(r);
    expect(retryReportMock).toHaveBeenCalledWith("88", undefined);
  });

  it("toReportError 提取后端 message 并保留状态码", () => {
    const error = {
      isAxiosError: true,
      message: "x",
      response: { status: 404, data: { message: "面试报告不存在" } },
    };
    const err = toReportError(error, "兜底");
    expect(err.status).toBe(404);
    expect(err.message).toBe("面试报告不存在");
  });

  it("waitForReport 在终态返回，跳过 GENERATING", async () => {
    vi.useFakeTimers();
    try {
      getReportMock
        .mockResolvedValueOnce(report({ status: "GENERATING" }))
        .mockResolvedValueOnce(report({ status: "SUCCESS" }));
      const promise = waitForReport("88");
      await vi.advanceTimersByTimeAsync(3000);
      await expect(promise).resolves.toMatchObject({ status: "SUCCESS" });
      expect(getReportMock).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });

  it("waitForReport 超时抛 ReportError(timeout)", async () => {
    vi.useFakeTimers();
    try {
      getReportMock.mockResolvedValue(report({ status: "GENERATING" }));
      const promise = waitForReport("88");
      // 在推进时间之前挂 rejection 处理器，避免 fake timers 推进期间
      // 产生 unhandled rejection 导致 vitest 报错
      const onReject = promise.catch((err: ReportError) => {
        expect(err).toBeInstanceOf(ReportError);
        expect(err.timeout).toBe(true);
      });
      await vi.advanceTimersByTimeAsync(180_000 + 100);
      await onReject;
    } finally {
      vi.useRealTimers();
    }
  });
});
