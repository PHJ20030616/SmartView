/**
 * 历史面试页面测试（Task 7.1）
 *
 * 覆盖：
 * - 列表渲染：方向/会话状态/报告状态标签
 * - 进行中的会话提供"继续面试"入口并跳转会话语
 * - 已结束且有报告的会话提供"查看报告"入口并跳转报告页
 * - 无报告（如已取消）的会话不提供操作入口
 * - 空列表展示空态文案
 */
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App as AntApp } from "antd";
import {
  MemoryRouter,
  Route,
  Routes,
  useSearchParams,
} from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { listInterviewSessionsApi } from "../../features/interview";
import type { components } from "../../api/generated/schema";
import InterviewHistoryPage from "./InterviewHistoryPage";

// 模拟 API 层：不发起真实请求，聚焦页面渲染与跳转
vi.mock("../../features/interview", () => ({
  listInterviewSessionsApi: vi.fn(),
}));

const listInterviewSessionsApiMock = vi.mocked(listInterviewSessionsApi);

type Summary = components["schemas"]["InterviewSessionSummary"];
type InterviewSessionPage = components["schemas"]["InterviewSessionPage"];

/** 构造分页响应 */
function pageOf(items: Summary[], total = items.length): InterviewSessionPage {
  return { items, page: 1, size: 10, total };
}

/** 会话摘要构造器：只填测试关心的字段 */
function summary(overrides: Partial<Summary> & { id: string }): Summary {
  return {
    userId: "7",
    resumeProfileId: "10",
    roleDirection: "JAVA_BACKEND",
    status: "COMPLETED",
    questionCount: 8,
    createdAt: "2026-08-17T10:00:00+08:00",
    ...overrides,
  } as Summary;
}

function renderHistoryPage() {
  return render(
    <AntApp>
      <MemoryRouter initialEntries={["/interview/history"]}>
        <Routes>
          <Route path="/interview/history" element={<InterviewHistoryPage />} />
          {/* 占位页：读取查询参数并渲染，用于断言跳转携带了正确的会话 ID */}
          <Route
            path="/interview/session"
            element={<SessionStub />}
          />
          <Route path="/report" element={<ReportStub />} />
        </Routes>
      </MemoryRouter>
    </AntApp>,
  );
}

/** 会话页占位：展示 ?sessionId= 参数，验证"继续面试"跳转链路 */
function SessionStub() {
  const [params] = useSearchParams();
  return <div>面试会话页 sessionId={params.get("sessionId") ?? "无"}</div>;
}

/** 报告页占位：展示 ?sessionId= 参数，验证"查看报告"跳转链路 */
function ReportStub() {
  const [params] = useSearchParams();
  return <div>报告页 sessionId={params.get("sessionId") ?? "无"}</div>;
}

describe("历史面试页面", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("加载成功后展示会话列表与状态标签", async () => {
    listInterviewSessionsApiMock.mockResolvedValue(
      pageOf([
        summary({
          id: "66",
          roleDirection: "JAVA_BACKEND",
          status: "COMPLETED",
          reportId: "500",
          reportStatus: "SUCCESS",
        }),
        summary({
          id: "67",
          roleDirection: "AGENT_DEVELOPMENT",
          status: "IN_PROGRESS",
        }),
        summary({
          id: "68",
          roleDirection: "AGENT_DEVELOPMENT",
          status: "CANCELLED",
        }),
      ]),
    );

    renderHistoryPage();

    // 方向标签（同一方向可能出现在多行，用 findAllByText 断言至少存在）
    expect(await screen.findAllByText("Java 后端")).not.toHaveLength(0);
    expect(screen.getAllByText("Agent 开发")).not.toHaveLength(0);
    // 状态标签
    expect(screen.getByText("已完成")).toBeInTheDocument();
    expect(screen.getByText("面试中")).toBeInTheDocument();
    expect(screen.getByText("已取消")).toBeInTheDocument();
    // 报告状态：成功/无报告
    expect(screen.getByText("已生成")).toBeInTheDocument();
    // 操作入口：进行中可继续面试，已完成可查看报告，已取消无入口
    expect(screen.getByRole("button", { name: "继续面试" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "查看报告" })).toBeInTheDocument();
  });

  it("点击继续面试跳转会话语并携带 sessionId", async () => {
    listInterviewSessionsApiMock.mockResolvedValue(
      pageOf([summary({ id: "66", status: "IN_PROGRESS" })]),
    );

    const user = userEvent.setup();
    renderHistoryPage();

    await user.click(await screen.findByRole("button", { name: "继续面试" }));

    // 会话页通过 ?sessionId= 恢复现场（验收：历史会话可继续）
    expect(screen.getByText("面试会话页 sessionId=66")).toBeInTheDocument();
  });

  it("点击查看报告跳转报告页并携带 sessionId", async () => {
    listInterviewSessionsApiMock.mockResolvedValue(
      pageOf([
        summary({
          id: "66",
          status: "COMPLETED",
          reportId: "500",
          reportStatus: "SUCCESS",
        }),
      ]),
    );

    const user = userEvent.setup();
    renderHistoryPage();

    await user.click(await screen.findByRole("button", { name: "查看报告" }));

    // 报告页通过 ?sessionId= 进入（验收：可从历史会话进入已完成报告）
    expect(screen.getByText("报告页 sessionId=66")).toBeInTheDocument();
  });

  it("无报告（如已取消）的会话不提供操作入口", async () => {
    listInterviewSessionsApiMock.mockResolvedValue(
      pageOf([
        summary({ id: "66", status: "CANCELLED" }),
        summary({ id: "67", status: "FAILED" }),
      ]),
    );

    renderHistoryPage();

    await screen.findByText("已取消");
    // 取消/失败的会话没有"继续面试"或"查看报告"按钮
    expect(
      screen.queryByRole("button", { name: /继续面试|查看报告/ }),
    ).not.toBeInTheDocument();
  });

  it("报告生成中的会话同样提供查看报告入口（报告页会轮询）", async () => {
    listInterviewSessionsApiMock.mockResolvedValue(
      pageOf([
        summary({
          id: "66",
          status: "REPORTING",
          reportId: "500",
          reportStatus: "GENERATING",
        }),
      ]),
    );

    renderHistoryPage();

    expect(await screen.findByText("生成中")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "查看报告" })).toBeInTheDocument();
  });

  it("空列表展示空态文案", async () => {
    listInterviewSessionsApiMock.mockResolvedValue(pageOf([]));

    renderHistoryPage();

    expect(
      await screen.findByText("暂无历史面试，从简历画像页开始你的第一场面试吧"),
    ).toBeInTheDocument();
  });
});
