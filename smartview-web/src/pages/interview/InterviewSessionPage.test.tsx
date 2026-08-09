import { App as AntApp } from "antd";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { createMemoryRouter, Outlet, RouterProvider } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { components } from "../../api/generated/schema";
import * as interviewService from "../../features/interview";
import InterviewSessionPage from "./InterviewSessionPage";

type InterviewSession = components["schemas"]["InterviewSession"];
type SubmitAnswerData = components["schemas"]["SubmitAnswerData"];

vi.mock("../../features/interview", () => ({
  createSession: vi.fn(),
  finishSession: vi.fn(),
  isConflictError: vi.fn(() => false),
  restoreSession: vi.fn(),
  submitAnswer: vi.fn(),
  toInterviewError: (error: unknown, fallback: string) =>
    error instanceof Error ? error : new Error(fallback),
}));

const restoreSessionMock = vi.mocked(interviewService.restoreSession);
const createSessionMock = vi.mocked(interviewService.createSession);
const submitAnswerMock = vi.mocked(interviewService.submitAnswer);
const finishSessionMock = vi.mocked(interviewService.finishSession);
const isConflictErrorMock = vi.mocked(interviewService.isConflictError);

function activeSession(): InterviewSession {
  return {
    id: "1",
    userId: "7",
    resumeProfileId: "10",
    roleDirection: "JAVA_BACKEND",
    status: "IN_PROGRESS",
    questionCount: 2,
    expectedMinQuestions: 5,
    expectedMaxQuestions: 8,
    answers: [
      {
        question: { id: "11", sessionId: "1", questionOrder: 1, questionText: "volatile 的作用？" },
        answerText: "保证可见性与禁止重排",
        durationSeconds: 40,
        submittedAt: "2026-08-09T10:00:00Z",
        evaluation: { score: 85, level: "GOOD", evaluationText: "要点清晰" },
      },
    ],
    currentQuestion: { id: "12", sessionId: "1", questionOrder: 2, questionText: "synchronized 与 volatile 的区别？" },
  };
}

function renderSessionPage(initialEntry: string) {
  const router = createMemoryRouter(
    [
      {
        element: <Outlet />,
        children: [
          { path: "/interview/session", element: <InterviewSessionPage /> },
          { path: "/", element: <div>首页</div> },
          { path: "/interview", element: <div>面试入口</div> },
        ],
      },
    ],
    { initialEntries: [initialEntry] },
  );
  render(
    <AntApp>
      <RouterProvider router={router} />
    </AntApp>,
  );
  return router;
}

describe("面试会话页面", () => {
  beforeEach(() => {
    // 清空 mock 调用记录，保证每个用例独立统计（沿用 LoginPage.test 模式）
    vi.clearAllMocks();
  });

  it("按 sessionId 恢复并展示当前题与历史问答", async () => {
    restoreSessionMock.mockResolvedValue(activeSession());
    renderSessionPage("/interview/session?sessionId=1");

    expect(await screen.findByText("synchronized 与 volatile 的区别？")).toBeTruthy();
    expect(screen.getByText("volatile 的作用？")).toBeTruthy();
    expect(screen.getByText(/保证可见性与禁止重排/)).toBeTruthy();
    expect(screen.getByText(/85/)).toBeTruthy();
    // 进度展示：已完成 2 题 · 预计 5~8 题
    expect(screen.getByText(/已完成 2 题/)).toBeTruthy();
    // 内部字段不得展示
    expect(screen.queryByText(/current_stage|stagePlan|BASIC/)).toBeNull();
  });

  it("无 sessionId 时创建会话并把 sessionId 写回 URL（刷新可恢复）", async () => {
    const s = activeSession();
    createSessionMock.mockResolvedValue(s);
    const router = renderSessionPage("/interview/session?profileId=10&roleDirection=JAVA_BACKEND");

    expect(await screen.findByText("synchronized 与 volatile 的区别？")).toBeTruthy();
    // 创建时携带取消信号（组件卸载中断请求）
    expect(createSessionMock).toHaveBeenCalledWith("10", "JAVA_BACKEND", expect.any(AbortSignal));
    // 创建后 URL 补上 sessionId，且不重复发起恢复请求
    await waitFor(() =>
      expect(router.state.location.search).toContain("sessionId=1"),
    );
    expect(restoreSessionMock).not.toHaveBeenCalled();
  });

  it("提交回答后追加历史并推进当前题", async () => {
    restoreSessionMock.mockResolvedValue(activeSession());
    submitAnswerMock.mockResolvedValue({
      answerId: "102",
      evaluation: { score: 90, level: "GOOD" },
      nextQuestion: { id: "13", sessionId: "1", questionOrder: 3, questionText: "第三题" },
      sessionStatus: "IN_PROGRESS",
    } as SubmitAnswerData);
    renderSessionPage("/interview/session?sessionId=1");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("回答内容"), "回答内容");
    await user.click(screen.getByRole("button", { name: /提交回答/ }));

    expect(await screen.findByText("第三题")).toBeTruthy();
    expect(submitAnswerMock).toHaveBeenCalledWith(
      "1",
      expect.objectContaining({ id: "12" }),
      "回答内容",
      expect.any(Number),
    );
    // 提交成功的问答进入历史
    expect(screen.getByText("回答内容")).toBeTruthy();
  });

  it("409 冲突时对账刷新会话", async () => {
    isConflictErrorMock.mockReturnValue(true);
    const fresh = {
      ...activeSession(),
      currentQuestion: { id: "20", sessionId: "1", questionOrder: 3, questionText: "对账后的题" },
    };
    // 首次进入恢复返回进行中会话；409 对账后的恢复返回最新会话
    restoreSessionMock.mockResolvedValueOnce(activeSession()).mockResolvedValue(fresh);
    submitAnswerMock.mockRejectedValue(new Error("409"));
    renderSessionPage("/interview/session?sessionId=1");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("回答内容"), "回答内容");
    await user.click(screen.getByRole("button", { name: /提交回答/ }));

    expect(await screen.findByText("对账后的题")).toBeTruthy();
    expect(restoreSessionMock).toHaveBeenCalledTimes(2);
  });

  it("提前结束经确认后进入已结束状态", async () => {
    restoreSessionMock.mockResolvedValue(activeSession());
    finishSessionMock.mockResolvedValue({
      ...activeSession(),
      status: "COMPLETED",
      currentQuestion: undefined,
    });
    renderSessionPage("/interview/session?sessionId=1");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: /结束面试/ }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /结束面试/ }));

    expect(await screen.findByText("面试已结束")).toBeTruthy();
    expect(finishSessionMock).toHaveBeenCalledWith("1");
  });

  it("恢复即已结束的会话直接展示已结束", async () => {
    restoreSessionMock.mockResolvedValue({
      ...activeSession(),
      status: "COMPLETED",
      currentQuestion: undefined,
    });
    renderSessionPage("/interview/session?sessionId=1");

    expect(await screen.findByText("面试已结束")).toBeTruthy();
  });
});
