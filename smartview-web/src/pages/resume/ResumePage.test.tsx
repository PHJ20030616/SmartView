/**
 * 简历上传页回归测试
 *
 * 覆盖两类回归场景：
 * 1. StrictMode 下 mountedRef 卸载守卫：React 18+ 开发模式的 StrictMode 会在挂载时
 *    额外执行一次 setup -> cleanup -> setup，若 effect 的 setup 不把 mountedRef
 *    恢复为 true，cleanup 会将其置为 false，导致上传完成后无法切换到“解析中”状态、
 *    解析成功后也无法跳转确认页（页面会一直停留在“上传中”）。
 * 2. 拖拽区布局结构：拖拽区必须位于可压缩的 upload-body 中、提交按钮必须位于
 *    upload-footer 中，否则拖拽区会撑破卡片（详见文件末尾的说明）。
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

/**
 * 拖拽区布局结构回归
 *
 * 历史问题：.resume-dropzone 曾用 min-height: 500px 硬撑高度，叠加上卡片内的
 * 步骤标签、标题、说明和提交按钮后整体超出卡片，拖拽区向下溢出卡片约 112px，
 * 右侧卡片也被拉伸得很高，视觉上红框区域明显比卡片还高。
 * 修复方案依赖两段结构：可弹性收缩的 upload-body（内含拖拽区）与固定在底部的
 * upload-footer（内含提交按钮）。这里锁定该结构，避免后续改回单一扁平容器。
 * 说明：真实高度约束属于 CSS 规则，jsdom 不加载项目样式且不做布局计算
 * （getBoundingClientRect 恒为 0），因此尺寸本身通过浏览器实测验证，不在此处断言。
 */
describe("简历上传页拖拽区布局结构", () => {
  it("拖拽区所在的 upload-body 可被压缩，且不包含提交按钮", () => {
    renderResumePage();
    const body = document.querySelector(".upload-body");
    expect(body).not.toBeNull();
    // 拖拽区必须位于 upload-body 内，才能随卡片剩余空间伸缩
    expect(body?.querySelector(".resume-dropzone")).not.toBeNull();
    // 提交按钮不属于 upload-body，否则会与拖拽区争夺同一份剩余空间
    expect(body?.querySelector("button")).toBeNull();
  });

  it("提交解析按钮固定在 upload-footer 中，与拖拽区同级", () => {
    renderResumePage();
    const footer = document.querySelector(".upload-footer");
    expect(footer).not.toBeNull();
    expect(
      footer?.querySelector("button")?.textContent?.trim(),
    ).toBe("提交解析");
    // 拖拽区不应出现在页脚容器内
    expect(footer?.querySelector(".resume-dropzone")).toBeNull();
  });
});
