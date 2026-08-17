import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import {
  fetchReport,
  fetchReportBySession,
  ReportError,
  retryReport,
  waitForReport,
} from "../../features/report";
import ReportPage from "./ReportPage";

type InterviewReport = components["schemas"]["InterviewReport"];

vi.mock("../../features/report", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../features/report")>();
  return {
    ...actual,
    fetchReportBySession: vi.fn(),
    fetchReport: vi.fn(),
    retryReport: vi.fn(),
    waitForReport: vi.fn(),
  };
});

const fetchBySessionMock = vi.mocked(fetchReportBySession);
const fetchReportMock = vi.mocked(fetchReport);
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

  it("无参数时展示空态引导", () => {
    renderPage("/report");
    expect(screen.getByText(/完成一次模拟面试后/)).toBeTruthy();
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
