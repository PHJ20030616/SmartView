/**
 * 历史面试页面（Task 7.1）
 *
 * 展示当前用户创建过的全部面试会话历史（分页）：
 * - 面试方向、会话状态、已提问数量、报告状态、创建时间
 * - 进行中的会话（IN_PROGRESS）可"继续面试"，进入会话页恢复现场
 * - 已结束且已生成报告的会话（COMPLETED/REPORTING）可"查看报告"
 * - 已软删除的会话由后端查询层自动过滤，不会出现在列表中
 *
 * 数据来源：GET /api/interview-sessions?page=&size=（后端只返回当前用户数据）
 */
import { ArrowLeftOutlined, ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Space, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import type { components } from "../../api/generated/schema";
import { listInterviewSessionsApi } from "../../features/interview";

type InterviewSessionSummary = components["schemas"]["InterviewSessionSummary"];
type InterviewSessionPage = components["schemas"]["InterviewSessionPage"];

/** 页面默认分页大小（与后端契约默认值一致） */
const DEFAULT_PAGE_SIZE = 10;

/** 面试方向 → 展示名（与面试会话页保持一致） */
const DIRECTION_LABEL: Record<string, string> = {
  JAVA_BACKEND: "Java 后端",
  AGENT_DEVELOPMENT: "Agent 开发",
};

/** 会话状态 → 展示标签 */
const SESSION_STATUS_TAG: Record<string, { label: string; color: string }> = {
  CREATED: { label: "已创建", color: "default" },
  IN_PROGRESS: { label: "面试中", color: "processing" },
  REPORTING: { label: "报告生成中", color: "warning" },
  COMPLETED: { label: "已完成", color: "success" },
  CANCELLED: { label: "已取消", color: "default" },
  FAILED: { label: "异常失败", color: "error" },
};

/** 报告状态 → 展示标签（无报告时为 null，展示"-"） */
const REPORT_STATUS_TAG: Record<string, { label: string; color: string }> = {
  GENERATING: { label: "生成中", color: "warning" },
  SUCCESS: { label: "已生成", color: "success" },
  FAILED: { label: "生成失败", color: "error" },
};

export default function InterviewHistoryPage() {
  const navigate = useNavigate();
  const location = useLocation();
  // 分页状态：切页/改每页条数时重新拉取
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [data, setData] = useState<InterviewSessionPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // 页面销毁时中止未完成请求，避免在已卸载组件上 setState
  const mountedRef = useRef(true);
  // 保存最近一次请求的 AbortController：快速翻页时先取消上一轮请求，
  // 防止"先发的慢响应后到"覆盖新数据造成错页展示（后发覆盖先发竞态）
  const controllerRef = useRef<AbortController | null>(null);

  /** 返回上一页；直接打开本页（无历史记录）时回退到面试工作台 */
  const handleBack = () => {
    if (location.key !== "default") {
      navigate(-1);
    } else {
      navigate("/interview");
    }
  };

  const load = useCallback(async (targetPage: number, targetSize: number) => {
    // 取消上一轮未完成的请求，保证页面上始终只展示最新一次请求的结果
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setLoading(true);
    setError(null);
    try {
      const result = await listInterviewSessionsApi(targetPage, targetSize, controller.signal);
      if (!mountedRef.current || controller.signal.aborted) return;
      setData(result);
      // 切页后若当前页已无数据且不是第一页，回退到最后一页重新拉取；
      // 仅当请求页码与计算出的最后一页不同才回退（递归后页码相等即停止），
      // 避免后端数据持续变化导致无限递归
      if (result.items.length === 0 && result.page > 1 && result.total > 0) {
        const lastPage = Math.ceil(result.total / result.size);
        if (result.page !== lastPage) {
          void load(lastPage, targetSize);
          return;
        }
      }
      setPage(result.page);
    } catch (err) {
      if (mountedRef.current && !controller.signal.aborted) {
        setError(err instanceof Error ? err.message : "历史面试加载失败，请重试");
      }
    } finally {
      // 只有当前请求才允许清理 loading 态，避免被已取消的旧请求提前复位
      if (mountedRef.current && controllerRef.current === controller) {
        setLoading(false);
        controllerRef.current = null;
      }
    }
  }, []);

  useEffect(() => {
    mountedRef.current = true;
    void load(page, pageSize);
    return () => {
      mountedRef.current = false;
      controllerRef.current?.abort();
    };
    // 首次挂载只按初始分页加载；后续翻页通过 load 直接调用，避免重复请求
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /** 翻页或调整每页条数：重置到目标页并重新拉取 */
  const handleTableChange = (nextPage: number, nextSize: number) => {
    setPage(nextPage);
    setPageSize(nextSize);
    void load(nextPage, nextSize);
  };

  const columns: ColumnsType<InterviewSessionSummary> = [
    {
      title: "面试方向",
      dataIndex: "roleDirection",
      key: "roleDirection",
      width: 130,
      render: (direction: InterviewSessionSummary["roleDirection"]) =>
        direction ? (
          <Tag color="geekblue">{DIRECTION_LABEL[direction] ?? direction}</Tag>
        ) : (
          "-"
        ),
    },
    {
      title: "会话状态",
      dataIndex: "status",
      key: "status",
      width: 130,
      render: (status: InterviewSessionSummary["status"]) => {
        const tag = status ? (SESSION_STATUS_TAG[status] ?? { label: status, color: "default" }) : null;
        return tag ? <Tag color={tag.color}>{tag.label}</Tag> : "-";
      },
    },
    {
      title: "提问数",
      dataIndex: "questionCount",
      key: "questionCount",
      width: 90,
      render: (count?: number | null) => count ?? "-",
    },
    {
      title: "报告状态",
      dataIndex: "reportStatus",
      key: "reportStatus",
      width: 120,
      render: (status: InterviewSessionSummary["reportStatus"]) => {
        const tag = status ? (REPORT_STATUS_TAG[status] ?? { label: status, color: "default" }) : null;
        return tag ? <Tag color={tag.color}>{tag.label}</Tag> : <Typography.Text type="secondary">-</Typography.Text>;
      },
    },
    {
      title: "创建时间",
      dataIndex: "createdAt",
      key: "createdAt",
      width: 200,
      render: (time?: string | null) =>
        time ? new Date(time).toLocaleString("zh-CN") : "-",
    },
    {
      title: "操作",
      key: "actions",
      width: 140,
      render: (_, record) => {
        // 进行中的会话：进入会话页按 sessionId 恢复现场
        if (record.status === "IN_PROGRESS") {
          return (
            <Button
              size="small"
              type="link"
              onClick={() =>
                navigate(`/interview/session?sessionId=${encodeURIComponent(record.id)}`)
              }
            >
              继续面试
            </Button>
          );
        }
        // 已结束且有报告（生成中/成功/失败均可进入报告页，报告页自会轮询或重试）
        if (record.reportId && (record.status === "COMPLETED" || record.status === "REPORTING")) {
          return (
            <Button
              size="small"
              type="link"
              onClick={() =>
                navigate(`/report?sessionId=${encodeURIComponent(record.id)}`)
              }
            >
              查看报告
            </Button>
          );
        }
        return <Typography.Text type="secondary">-</Typography.Text>;
      },
    },
  ];

  return (
    <div className="page-stack">
      <section className="page-header">
        <Space align="center" size={12}>
          <Button
            aria-label="返回面试工作台"
            icon={<ArrowLeftOutlined aria-hidden="true" />}
            onClick={handleBack}
            type="text"
          />
          <Typography.Title className="page-title" level={1}>
            历史面试
          </Typography.Title>
        </Space>
        <Typography.Paragraph className="page-subtitle">
          查看过往面试会话，进行中的面试可继续，已结束的面试可查看复盘报告。
        </Typography.Paragraph>
      </section>

      {error && (
        <Alert
          action={
            <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={() => void load(page, pageSize)} size="small">
              重试
            </Button>
          }
          description={error}
          message="加载失败"
          showIcon
          type="error"
        />
      )}

      <Table<InterviewSessionSummary>
        columns={columns}
        dataSource={data?.items ?? []}
        loading={loading}
        locale={{ emptyText: "暂无历史面试，从简历画像页开始你的第一场面试吧" }}
        onChange={(pagination) =>
          handleTableChange(pagination.current ?? 1, pagination.pageSize ?? DEFAULT_PAGE_SIZE)
        }
        pagination={{
          current: data?.page ?? page,
          pageSize: data?.size ?? pageSize,
          total: data?.total ?? 0,
          showSizeChanger: true,
          showQuickJumper: true,
          pageSizeOptions: [10, 20, 50],
          showTotal: (total) => `共 ${total} 条`,
        }}
        rowKey="id"
        scroll={{ x: 810 }}
      />
    </div>
  );
}
