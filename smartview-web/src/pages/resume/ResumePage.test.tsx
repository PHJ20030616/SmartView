/**
 * 简历上传页回归测试
 *
 * 覆盖 StrictMode 下 mountedRef 卸载守卫的回归场景：
 * React 18+ 开发模式的 StrictMode 会在挂载时额外执行一次 setup -> cleanup -> setup，
 * 若 effect 的 setup 不把 mountedRef 恢复为 true，cleanup 会将其置为 false，
 * 导致上传完成后无法切换到“解析中”状态、解析成功后也无法跳转确认页
 * （页面会一直停留在“上传中”）。
 */
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App as AntApp } from "antd";
import { StrictMode } from "react";
import {
  MemoryRouter,
  Route,
  Routes,
  useParams,
} from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ResumeFile } from "../../features/resume";
import { uploadAndWaitForParse } from "../../features/resume";
import ResumePage from "./ResumePage";

// 模拟服务层：不发起真实上传与轮询请求，聚焦验证页面状态流转与跳转
vi.mock("../../features/resume", () => ({
  isResumeParseAbortError: (error: unknown) =>
    error instanceof Error && error.name === "AbortError",
  uploadAndWaitForParse: vi.fn(),
}));

const uploadAndWaitForParseMock = vi.mocked(uploadAndWaitForParse);

/** 确认页占位组件：用于断言解析成功后是否跳转到确认路由 */
function ConfirmPageStub() {
  const { profileId } = useParams<{ profileId: string }>();
  return <div>确认页画像：{profileId}</div>;
}

/** 在 StrictMode 下渲染上传页与确认页占位路由 */
function renderResumePage() {
  return render(
    <StrictMode>
      <AntApp>
        <MemoryRouter initialEntries={["/resume"]}>
          <Routes>
            <Route path="/resume" element={<ResumePage />} />
            <Route
              path="/resume/confirm/:profileId"
              element={<ConfirmPageStub />}
            />
          </Routes>
        </MemoryRouter>
      </AntApp>
    </StrictMode>,
  );
}

describe("简历上传页（StrictMode 回归）", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("StrictMode 下解析成功后可切换到解析中状态并跳转确认页", async () => {
    // 模拟服务行为：上传完成后先回调“parse”阶段，稍后返回解析成功结果
    uploadAndWaitForParseMock.mockImplementation(async (_file, onPhase) => {
      onPhase?.("parse");
      // 让“解析中”状态有足够的渲染窗口，便于断言
      await new Promise((resolve) => setTimeout(resolve, 200));
      return {
        id: "resume-1",
        parseStatus: "SUCCESS",
        profileId: "profile-1",
      } as ResumeFile;
    });

    const user = userEvent.setup();
    renderResumePage();

    // 选择 PDF 文件，触发 Upload 的 onChange 将文件加入列表
    const fileInput = document.querySelector(
      'input[type="file"]',
    ) as HTMLInputElement;
    await user.upload(
      fileInput,
      new File(["resume"], "张三_Java简历.pdf", { type: "application/pdf" }),
    );

    await user.click(screen.getByRole("button", { name: "提交解析" }));

    // 回归断言 1：上传完成后应出现“正在解析”，证明 mountedRef 守卫在 StrictMode 下为 true
    expect(await screen.findByText(/正在解析/)).toBeInTheDocument();

    // 回归断言 2：解析成功后应跳转到确认页
    expect(
      await screen.findByText("确认页画像：profile-1"),
    ).toBeInTheDocument();
    expect(uploadAndWaitForParseMock).toHaveBeenCalledTimes(1);
  });
});
