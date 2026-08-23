/**
 * 历史简历页面测试（Task 7.1）
 *
 * 覆盖：
 * - 列表渲染：文件名、解析状态标签、上传时间
 * - 解析成功的简历提供"查看画像"入口并正确跳转
 * - 空列表展示空态文案
 * - 加载失败展示错误信息并可重试
 * - 翻页时携带新的 page/size 重新请求
 */
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App as AntApp } from "antd";
import { MemoryRouter, Route, Routes, useParams } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { deleteResumeApi, getResumeHistoryApi } from "../../features/resume";
import type { ResumeFilePage } from "../../features/resume";
import ResumeHistoryPage from "./ResumeHistoryPage";

// 模拟 API 层：不发起真实请求，聚焦页面渲染与跳转
vi.mock("../../features/resume", () => ({
  getResumeHistoryApi: vi.fn(),
  deleteResumeApi: vi.fn(),
}));

const getResumeHistoryApiMock = vi.mocked(getResumeHistoryApi);
const deleteResumeApiMock = vi.mocked(deleteResumeApi);

/** 确认页占位组件：用于断言"查看画像"是否跳转到确认路由 */
function ConfirmPageStub() {
  const { profileId } = useParams<{ profileId: string }>();
  return <div>确认页画像：{profileId}</div>;
}

/** 构造分页响应（items 按需传入） */
function pageOf(items: ResumeFilePage["items"], total = items.length): ResumeFilePage {
  return { items, page: 1, size: 10, total };
}

function renderHistoryPage() {
  return render(
    <AntApp>
      <MemoryRouter initialEntries={["/resume/history"]}>
        <Routes>
          <Route path="/resume/history" element={<ResumeHistoryPage />} />
          <Route path="/resume/confirm/:profileId" element={<ConfirmPageStub />} />
        </Routes>
      </MemoryRouter>
    </AntApp>,
  );
}

describe("历史简历页面", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("加载成功后展示简历列表与解析状态", async () => {
    getResumeHistoryApiMock.mockResolvedValue(
      pageOf([
        {
          id: "1",
          userId: "7",
          originalFilename: "张三_Java开发.pdf",
          fileSize: 2 * 1024 * 1024,
          parseStatus: "SUCCESS",
          profileId: "10",
          uploadedAt: "2026-08-17T10:00:00+08:00",
        },
        {
          id: "2",
          userId: "7",
          originalFilename: "李四_简历.pdf",
          fileSize: 512 * 1024,
          parseStatus: "FAILED",
          errorMessage: "PDF 损坏，无法提取文本",
          uploadedAt: "2026-08-16T09:00:00+08:00",
        },
      ]),
    );

    renderHistoryPage();

    expect(await screen.findByText("张三_Java开发.pdf")).toBeInTheDocument();
    expect(screen.getByText("李四_简历.pdf")).toBeInTheDocument();
    // 解析状态标签
    expect(screen.getByText("解析成功")).toBeInTheDocument();
    expect(screen.getByText("解析失败")).toBeInTheDocument();
    // 文件大小人类可读展示
    expect(screen.getByText("2.0 MB")).toBeInTheDocument();
    expect(screen.getByText("512.0 KB")).toBeInTheDocument();
    // 失败原因提示入口
    expect(screen.getByText("失败原因")).toBeInTheDocument();
    // 解析成功且有画像的简历提供查看画像入口
    expect(screen.getByRole("button", { name: "查看画像" })).toBeInTheDocument();
  });

  it("点击查看画像跳转到画像确认页", async () => {
    getResumeHistoryApiMock.mockResolvedValue(
      pageOf([
        {
          id: "1",
          userId: "7",
          originalFilename: "张三_Java开发.pdf",
          parseStatus: "SUCCESS",
          profileId: "10",
        },
      ]),
    );

    const user = userEvent.setup();
    renderHistoryPage();

    await user.click(await screen.findByRole("button", { name: "查看画像" }));

    expect(screen.getByText("确认页画像：10")).toBeInTheDocument();
  });

  it("解析失败或无画像的简历不提供查看画像入口", async () => {
    getResumeHistoryApiMock.mockResolvedValue(
      pageOf([
        {
          id: "1",
          userId: "7",
          originalFilename: "失败简历.pdf",
          parseStatus: "FAILED",
        },
        {
          id: "2",
          userId: "7",
          originalFilename: "未生成画像.pdf",
          parseStatus: "SUCCESS",
          // profileId 缺失：无法进入确认页
        },
      ]),
    );

    renderHistoryPage();

    await screen.findByText("失败简历.pdf");
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "查看画像" })).not.toBeInTheDocument();
    });
  });

  it("空列表展示空态文案", async () => {
    getResumeHistoryApiMock.mockResolvedValue(pageOf([]));

    renderHistoryPage();

    expect(
      await screen.findByText("暂无历史简历，去上传你的第一份简历吧"),
    ).toBeInTheDocument();
  });

  it("加载失败展示错误信息，点击重试后重新请求", async () => {
    getResumeHistoryApiMock
      .mockRejectedValueOnce(new Error("网络异常"))
      .mockResolvedValueOnce(
        pageOf([
          {
            id: "1",
            userId: "7",
            originalFilename: "重试成功.pdf",
            parseStatus: "SUCCESS",
          },
        ]),
      );

    const user = userEvent.setup();
    renderHistoryPage();

    expect(await screen.findByText("网络异常")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "重试" }));

    expect(await screen.findByText("重试成功.pdf")).toBeInTheDocument();
    expect(getResumeHistoryApiMock).toHaveBeenCalledTimes(2);
  });

  it("翻页时携带新的 page 参数重新请求", async () => {
    getResumeHistoryApiMock.mockImplementation(async (page) =>
      page === 1
        ? {
            items: [
              {
                id: "1",
                userId: "7",
                originalFilename: "第一页.pdf",
                parseStatus: "SUCCESS",
              },
            ],
            page: 1,
            size: 10,
            total: 11,
          }
        : {
            items: [
              {
                id: "11",
                userId: "7",
                originalFilename: "第二页.pdf",
                parseStatus: "SUCCESS",
              },
            ],
            page: 2,
            size: 10,
            total: 11,
          },
    );

    const user = userEvent.setup();
    renderHistoryPage();

    expect(await screen.findByText("第一页.pdf")).toBeInTheDocument();

    // 点击分页第 2 页
    await user.click(await screen.findByTitle("2"));

    expect(await screen.findByText("第二页.pdf")).toBeInTheDocument();
    // 第三个参数为 AbortSignal，只断言前两个分页参数
    expect(getResumeHistoryApiMock).toHaveBeenLastCalledWith(2, 10, expect.any(AbortSignal));
  });

  it("确认删除后调用删除接口并重新加载列表（Task 7.2）", async () => {
    getResumeHistoryApiMock
      .mockResolvedValueOnce(
        pageOf([
          {
            id: "1",
            userId: "7",
            originalFilename: "待删除.pdf",
            parseStatus: "SUCCESS",
          },
        ]),
      )
      .mockResolvedValueOnce(pageOf([]));
    deleteResumeApiMock.mockResolvedValue(undefined);

    const user = userEvent.setup();
    renderHistoryPage();

    await user.click(await screen.findByRole("button", { name: /删\s*除/ }));
    // 确认按钮文案与触发按钮不同（确认删除），避免与触发按钮匹配歧义
    await user.click(await screen.findByRole("button", { name: "确认删除" }));

    await waitFor(() => {
      expect(deleteResumeApiMock).toHaveBeenCalledWith("1");
    });
    // 删除成功后重新拉取列表（当前页已空，自动回退后仍保持空态）
    expect(getResumeHistoryApiMock).toHaveBeenCalledTimes(2);
    expect(
      await screen.findByText("暂无历史简历，去上传你的第一份简历吧"),
    ).toBeInTheDocument();
  });
});
