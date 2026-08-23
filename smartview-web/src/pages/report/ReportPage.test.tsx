import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import {
  fetchReport,
  fetchReportBySession,
  fetchReportList,
  ReportError,
  retryReport,
  waitForReport,
} from "../../features/report";
import ReportPage from "./ReportPage";

type InterviewReport = components["schemas"]["InterviewReport"];
type InterviewReportSummary = components["schemas"]["InterviewReportSummary"];

vi.mock("../../features/report", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../features/report")>();
  return {
    ...actual,
    fetchReportBySession: vi.fn(),
    fetchReport: vi.fn(),
    fetchReportList: vi.fn(),
    retryReport: vi.fn(),
    waitForReport: vi.fn(),
  };
});

const fetchBySessionMock = vi.mocked(fetchReportBySession);
const fetchReportMock = vi.mocked(fetchReport);
const fetchReportListMock = vi.mocked(fetchReportList);
const retryReportMock = vi.mocked(retryReport);
const waitForReportMock = vi.mocked(waitForReport);

function report(overrides: Partial<InterviewReport> = {}): InterviewReport {
  return {
    id: "88",
    sessionId: "66",
    userId: "7",
    resumeProfileId: "12",
    roleDirection: "JAVA_BACKEND",
    overallScore: 76,
    readinessLevel: "READY",
    roleFitScore: 82,
    summary: "整体表现良好",
    strengths: ["基础知识扎实"],
    weaknesses: ["分布式经验不足"],
    riskPoints: ["并发场景应对欠佳"],
    suggestions: [{ topic: "线程池", reason: "高频考点", resources: ["《Java 并发编程实战》"] }],
    coverage: { basicCoverage: 0.8, projectCoverage: 0.5, scenarioCoverage: 0.2 },
    referenceAnswers: [
      {
        questionId: "11",
        answerType: "BASIC_KEY_POINTS",
        referenceContent: "volatile 保证可见性",
        keyPoints: ["happens-before"],
      },
    ],
    answers: [
      {
        question: { id: "11", sessionId: "66", questionOrder: 1, questionText: "volatile 的作用？" },
        answerText: "保证可见性",
        evaluation: { score: 85, level: "GOOD", evaluationText: "要点清晰" },
      },
    ],
    status: "SUCCESS",
    ...overrides,
  } as InterviewReport;
}

/** 报告历史摘要（列表专用轻量模型）测试夹具 */
function summary(overrides: Partial<InterviewReportSummary> = {}): InterviewReportSummary {
  return {
    id: "88",
    sessionId: "66",
    userId: "7",
    roleDirection: "JAVA_BACKEND",
    overallScore: 76,
    readinessLevel: "READY",
    roleFitScore: 82,
    summary: "整体表现良好",
    status: "SUCCESS",
    generatedAt: "2026-08-20T10:00:00+08:00",
    createdAt: "2026-08-20T09:00:00+08:00",
    ...overrides,
  } as InterviewReportSummary;
}

function renderPage(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/report" element={<ReportPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("报告页面", () => {
  afterEach(() => vi.clearAllMocks());

  it("无参数时展示报告历史列表（空列表空态）", async () => {
    fetchReportListMock.mockResolvedValue({ items: [], page: 1, size: 10, total: 0 });
    renderPage("/report");
    expect(await screen.findByText(/暂无报告/)).toBeTruthy();
    expect(fetchReportListMock).toHaveBeenCalledWith(1, 10, expect.anything());
  });

  it("列表展示报告摘要，点击查看报告进入详情", async () => {
    fetchReportListMock.mockResolvedValue({
      items: [summary()],
      page: 1,
      size: 10,
      total: 1,
    });
    // 点击后按 reportId 直查详情（SUCCESS 直接展示）
    fetchReportMock.mockResolvedValue(report());
    renderPage("/report");

    expect(await screen.findByText(/Java 后端/)).toBeTruthy(); // 面试方向
    expect(screen.getByText("76")).toBeTruthy(); // 综合得分
    expect(screen.getByText(/已准备就绪/)).toBeTruthy(); // 准备度标签
    expect(screen.getByText(/已生成/)).toBeTruthy(); // 报告状态

    await userEvent.click(screen.getByRole("button", { name: /查看报告/ }));
    expect(fetchReportMock).toHaveBeenCalledWith("88", expect.anything());
    expect(await screen.findByText(/整体表现良好/)).toBeTruthy(); // 详情展示
  });

  it("列表加载失败展示错误并可重试", async () => {
    // 首次请求失败，点击重试后重新拉取成功
    fetchReportListMock
      .mockRejectedValueOnce(new ReportError("报告列表接口异常", 500))
      .mockResolvedValueOnce({ items: [summary()], page: 1, size: 10, total: 1 });
    renderPage("/report");

    expect(await screen.findByText(/报告列表接口异常/)).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: /重试/ }));
    expect(await screen.findByText(/Java 后端/)).toBeTruthy();
    expect(fetchReportListMock).toHaveBeenCalledTimes(2);
  });

  it("GENERATING 状态展示生成中并经 waitForReport 轮询", async () => {
    fetchBySessionMock.mockResolvedValue(report({ status: "GENERATING" }));
    // 轮询挂起不返回，保证「报告生成中」状态稳定可断言
    waitForReportMock.mockReturnValue(new Promise(() => {}));
    renderPage("/report?sessionId=66");

    expect(await screen.findByText(/报告生成中/)).toBeTruthy();
    expect(waitForReportMock).toHaveBeenCalledWith("88", expect.anything());
  });

  it("SUCCESS 展示评分/准备度/匹配度/覆盖/优劣势/逐题复盘", async () => {
    fetchBySessionMock.mockResolvedValue(report());
    renderPage("/report?sessionId=66");

    expect(await screen.findByText("76")).toBeTruthy(); // 综合得分
    expect(screen.getByText(/已准备就绪/)).toBeTruthy(); // 准备度标签
    expect(screen.getByText("82")).toBeTruthy(); // 岗位匹配度
    expect(screen.getByText(/基础知识扎实/)).toBeTruthy(); // 优势
    expect(screen.getByText(/分布式经验不足/)).toBeTruthy(); // 薄弱
    expect(screen.getByText(/线程池/)).toBeTruthy(); // 建议 topic
    expect(screen.getByText(/volatile 的作用/)).toBeTruthy(); // 题目
    expect(screen.getByText("保证可见性")).toBeTruthy(); // 我的回答
    expect(screen.getByText(/得分 85/)).toBeTruthy(); // 评估得分
  });

  it("FAILED 展示失败提示并可重试", async () => {
    fetchBySessionMock.mockResolvedValue(report({ status: "FAILED" }));
    retryReportMock.mockResolvedValue(report({ status: "GENERATING" }));
    // 重试后进入生成中轮询，挂起避免后续状态漂移
    waitForReportMock.mockReturnValue(new Promise(() => {}));
    renderPage("/report?sessionId=66");

    expect(await screen.findByText(/报告生成失败/)).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: /重试生成/ }));
    expect(retryReportMock).toHaveBeenCalledWith("88", expect.anything());
  });

  it("加载失败展示错误并回到报告页重载", async () => {
    fetchBySessionMock.mockRejectedValue(new ReportError("接口异常", 500));
    renderPage("/report?sessionId=66");
    expect(await screen.findByText(/接口异常/)).toBeTruthy();
  });
});
