/**
 * 简历确认页回归测试
 *
 * 覆盖 StrictMode 下 mountedRef 卸载守卫的回归场景：
 * StrictMode 开发模式会在挂载时额外执行 setup -> cleanup -> setup，
 * 若 effect 的 setup 不把 mountedRef 恢复为 true，cleanup 会将其置为 false，
 * 导致确认页加载画像完成后无法从“加载中”切换到“就绪”状态（页面一直转圈）。
 */
import { render, screen } from "@testing-library/react";
import { App as AntApp } from "antd";
import { StrictMode } from "react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ResumeProfile } from "../../features/resume";
import { fetchResumeProfile } from "../../features/resume";
import ResumeConfirmPage from "./ResumeConfirmPage";

// 模拟服务层：不发起真实请求，聚焦验证页面从加载态到就绪态的状态流转
vi.mock("../../features/resume", () => ({
  ResumeVectorizationError: class ResumeVectorizationError extends Error {},
  fetchResumeProfile: vi.fn(),
  retryResumeVectorization: vi.fn(),
  saveResumeProfile: vi.fn(),
  submitResumeConfirmation: vi.fn(),
  waitForResumeVectorization: vi.fn(),
}));

const fetchResumeProfileMock = vi.mocked(fetchResumeProfile);

/** 在 StrictMode 下渲染确认页 */
function renderConfirmPage() {
  return render(
    <StrictMode>
      <AntApp>
        <MemoryRouter initialEntries={["/resume/confirm/profile-1"]}>
          <Routes>
            <Route
              path="/resume/confirm/:profileId"
              element={<ResumeConfirmPage />}
            />
          </Routes>
        </MemoryRouter>
      </AntApp>
    </StrictMode>,
  );
}

describe("简历确认页（StrictMode 回归）", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("StrictMode 下加载画像完成后应渲染候选人信息", async () => {
    fetchResumeProfileMock.mockResolvedValue({
      id: "profile-1",
      userId: "user-1",
      resumeFileId: "resume-1",
      candidateName: "张三",
      confirmStatus: "UNCONFIRMED",
      version: 1,
    } as ResumeProfile);

    renderConfirmPage();

    // 回归断言：加载完成后应展示候选人姓名，证明 mountedRef 守卫在 StrictMode 下为 true
    expect(await screen.findByText("张三")).toBeInTheDocument();
    expect(fetchResumeProfileMock).toHaveBeenCalledWith(
      "profile-1",
      expect.any(AbortSignal),
    );
  });
});
