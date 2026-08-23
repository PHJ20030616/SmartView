/**
 * 报告历史列表（复盘报告页无参数入口）。
 *
 * 展示当前用户全部历史复盘报告（分页）：
 * - 面试方向、综合得分、准备度、报告状态、生成时间
 * - 生成中/成功/失败的报告均展示：点击"查看报告"进入详情页，
 *   详情页自会轮询生成结果或提供失败重试，与历史面试页入口行为一致
 *
 * 数据来源：GET /api/reports?page=&size=（后端只返回当前用户数据）
 */
import { ReloadOutlined } from "@ant-design/icons";
import { Alert, Button, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import type { components } from "../../api/generated/schema";
import {
  fetchReportList,
  READINESS_COLOR,
  READINESS_LABEL,
  ROLE_DIRECTION_LABEL,
  STATUS_LABEL,
  toReportError,
} from "../../features/report";

type InterviewReportSummary = components["schemas"]["InterviewReportSummary"];
type InterviewReportPage = components["schemas"]["InterviewReportPage"];

/** 页面默认分页大小（与后端契约默认值一致） */
const DEFAULT_PAGE_SIZE = 10;

/** 报告状态 → 标签颜色（与历史面试页报告状态标签保持一致） */
const STATUS_TAG_COLOR: Record<string, string> = {
  GENERATING: "warning",
  SUCCESS: "success",
  FAILED: "error",
};

export default function ReportList() {
  const navigate = useNavigate();
  // 分页状态：切页/改每页条数时重新拉取
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [data, setData] = useState<InterviewReportPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // 页面销毁时中止未完成请求，避免在已卸载组件上 setState
  const mountedRef = useRef(true);
  // 保存最近一次请求的 AbortController：快速翻页时先取消上一轮请求，
  // 防止"先发的慢响应后到"覆盖新数据造成错页展示（后发覆盖先发竞态）
  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(async (targetPage: number, targetSize: number) => {
    // 取消上一轮未完成的请求，保证页面上始终只展示最新一次请求的结果
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    setLoading(true);
    setError(null);
    try {
      const result = await fetchReportList(targetPage, targetSize, controller.signal);
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
        // 复用 toReportError 提取后端中文 message，避免展示 axios 英文默认文案
        setError(toReportError(err, "报告列表加载失败，请重试").message);
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

  const columns: ColumnsType<InterviewReportSummary> = [
    {
      title: "面试方向",
      dataIndex: "roleDirection",
      key: "roleDirection",
      width: 130,
      render: (direction: InterviewReportSummary["roleDirection"]) =>
        direction ? (
          <Tag color="geekblue">{ROLE_DIRECTION_LABEL[direction] ?? direction}</Tag>
        ) : (
          "-"
        ),
    },
    {
      title: "综合得分",
      dataIndex: "overallScore",
      key: "overallScore",
      width: 100,
      render: (score?: number | null) => score ?? "-",
    },
    {
      title: "准备度",
      dataIndex: "readinessLevel",
      key: "readinessLevel",
      width: 130,
      render: (level: InterviewReportSummary["readinessLevel"]) => {
        if (!level) return "-";
        const label = READINESS_LABEL[level] ?? level;
        const color = READINESS_COLOR[level] ?? "default";
        return <Tag color={color}>{label}</Tag>;
      },
    },
    {
      title: "报告状态",
      dataIndex: "status",
      key: "status",
      width: 110,
      render: (status: InterviewReportSummary["status"]) =>
        status ? (
          <Tag color={STATUS_TAG_COLOR[status] ?? "default"}>{STATUS_LABEL[status] ?? status}</Tag>
        ) : (
          "-"
        ),
    },
    {
      title: "生成时间",
      key: "generatedAt",
      width: 180,
      // 生成中/失败的报告无 generatedAt，回退展示创建时间
      render: (_, record) => {
        const time = record.generatedAt ?? record.createdAt;
        return time ? new Date(time).toLocaleString("zh-CN") : "-";
      },
    },
    {
      title: "操作",
      key: "actions",
      width: 110,
      render: (_, record) => (
        <Button
          size="small"
          type="link"
          onClick={() => navigate(`/report?reportId=${encodeURIComponent(record.id)}`)}
        >
          查看报告
        </Button>
      ),
    },
  ];

  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>
          复盘报告
        </Typography.Title>
        <Typography.Paragraph className="page-subtitle">
          查看过往面试的复盘报告，生成中或失败的报告可进入详情继续查询或重试。
        </Typography.Paragraph>
      </section>

      {error && (
        <Alert
          action={
            <Button
              icon={<ReloadOutlined aria-hidden="true" />}
              onClick={() => void load(page, pageSize)}
              size="small"
            >
              重试
            </Button>
          }
          description={error}
          message="加载失败"
          showIcon
          type="error"
        />
      )}

      <Table<InterviewReportSummary>
        columns={columns}
        dataSource={data?.items ?? []}
        loading={loading}
        locale={{ emptyText: "暂无报告，完成一次模拟面试后，这里会展示每场面试的复盘报告。" }}
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
        scroll={{ x: 820 }}
      />
    </div>
  );
}
