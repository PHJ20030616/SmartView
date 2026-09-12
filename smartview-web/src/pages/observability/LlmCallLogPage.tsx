/**
 * LLM 调用观测看板（只读，运维视图）。
 *
 * 数据来源：GET /api/llm-calls。后端只对该接口的运维白名单账号开放，
 * 非白名单账号会收到 403，页面据此展示明确的中文说明而非空白。
 * 页面职责单一：展示调用记录与汇总统计，不做任何写操作。
 */
import { ReloadOutlined } from "@ant-design/icons";
import {
  Alert,
  Button,
  DatePicker,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from "antd";
import type { TableColumnsType } from "antd";
import type { Dayjs } from "dayjs";
import { useCallback, useEffect, useRef, useState } from "react";

import type { components } from "../../api/generated/schema";
import {
  fetchLlmCalls,
  SCENE_LABEL,
  STATUS_COLOR,
  STATUS_LABEL,
  toLlmLogError,
  type LlmCallQuery,
} from "../../features/llmLog";

type LlmCallSummary = components["schemas"]["LlmCallSummary"];
type LlmCallPage = components["schemas"]["LlmCallPage"];

/** 页面默认分页大小（与契约默认值一致） */
const DEFAULT_PAGE_SIZE = 20;

/** 场景下拉项：第一项为空值，表示不过滤 */
const SCENE_OPTIONS = [
  { value: "", label: "全部场景" },
  ...Object.entries(SCENE_LABEL).map(([value, label]) => ({ value, label })),
];

export default function LlmCallLogPage() {
  // 过滤条件与分页状态：任一项变化都通过 load 重新拉取
  const [scene, setScene] = useState("");
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [data, setData] = useState<LlmCallPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ message: string; forbidden: boolean } | null>(null);

  const mountedRef = useRef(true);
  // 取消上一轮未完成的请求，避免"先发的慢响应后到"覆盖新条件的结果
  const controllerRef = useRef<AbortController | null>(null);

  const load = useCallback(
    (
      targetPage: number,
      targetSize: number,
      targetScene: string,
      targetRange: [Dayjs, Dayjs] | null,
    ) => {
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;
      setLoading(true);
      setError(null);

      const query: LlmCallQuery = {
        scene: targetScene || undefined,
        page: targetPage,
        size: targetSize,
        // 区间取左闭右开：结束时间加一天，符合"按天筛选"的直觉。
        // 刻意不用 toISOString()：它输出带 Z 的 UTC 串，而后端把入参解析成本地时间
        // （LocalDateTime 会静默丢弃时区），东八区下会让窗口整体偏移 8 小时，
        // 漏掉所选日期 16:00 之后的记录。这里按本地时间格式化，与库内 created_at 对齐。
        from: targetRange
          ? targetRange[0].startOf("day").format("YYYY-MM-DDTHH:mm:ss")
          : undefined,
        to: targetRange
          ? targetRange[1].add(1, "day").startOf("day").format("YYYY-MM-DDTHH:mm:ss")
          : undefined,
      };

      void fetchLlmCalls(query, controller.signal)
        .then((result) => {
          if (!mountedRef.current || controller.signal.aborted) return;
          setData(result);
          setPage(result.page);
          setPageSize(result.size);
        })
        .catch((err: unknown) => {
          if (!mountedRef.current || controller.signal.aborted) return;
          const normalized = toLlmLogError(err, "调用记录加载失败，请重试");
          setError({
            message: normalized.message,
            // 403 单独标记：这不是故障，而是权限说明，重试没有意义
            forbidden: normalized.status === 403,
          });
        })
        .finally(() => {
          if (mountedRef.current && controllerRef.current === controller) {
            setLoading(false);
            controllerRef.current = null;
          }
        });
    },
    [],
  );

  useEffect(() => {
    mountedRef.current = true;
    load(1, DEFAULT_PAGE_SIZE, "", null);
    return () => {
      mountedRef.current = false;
      controllerRef.current?.abort();
    };
  }, [load]);

  const columns: TableColumnsType<LlmCallSummary> = [
    {
      title: "调用时间",
      key: "createdAt",
      width: 170,
      render: (_, record) =>
        record.createdAt ? new Date(record.createdAt).toLocaleString("zh-CN") : "-",
    },
    {
      title: "场景",
      dataIndex: "scene",
      key: "scene",
      width: 140,
      render: (value: string) => SCENE_LABEL[value] ?? value,
    },
    { title: "模型", dataIndex: "model", key: "model", width: 160 },
    {
      title: "结果",
      dataIndex: "status",
      key: "status",
      width: 90,
      render: (value: string, record) =>
        value === "FAILED" ? (
          <Tag color={STATUS_COLOR.FAILED}>{record.errorCode ?? STATUS_LABEL.FAILED}</Tag>
        ) : (
          <Tag color={STATUS_COLOR.SUCCESS}>{STATUS_LABEL[value] ?? value}</Tag>
        ),
    },
    {
      title: "耗时",
      dataIndex: "latencyMs",
      key: "latencyMs",
      width: 100,
      render: (value?: number | null) => (value == null ? "-" : `${value} ms`),
    },
    {
      title: "Token（入/出）",
      key: "tokens",
      width: 150,
      render: (_, record) =>
        record.tokenInput == null && record.tokenOutput == null
          ? "-"
          : `${record.tokenInput ?? "-"} / ${record.tokenOutput ?? "-"}`,
    },
    {
      title: "修复重试",
      dataIndex: "retryAttempt",
      key: "retryAttempt",
      width: 110,
      render: (value: number) => (value > 0 ? <Tag color="warning">第 {value} 次</Tag> : "首次"),
    },
    {
      title: "链路追踪 ID",
      dataIndex: "traceId",
      key: "traceId",
      width: 300,
      render: (value?: string | null) => value ?? "-",
    },
  ];

  const stats = data?.stats;

  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>
          LLM 调用观测
        </Typography.Title>
        <Typography.Paragraph className="page-subtitle">
          查看大模型调用的场景分布、成功率、P95 延迟与 token
          消耗，用于定位 prompt 迭代与模型波动带来的变化。
        </Typography.Paragraph>
      </section>

      <Space wrap>
        <Select
          aria-label="调用场景"
          options={SCENE_OPTIONS}
          style={{ width: 180 }}
          value={scene}
          onChange={(value) => {
            setScene(value);
            load(1, pageSize, value, range);
          }}
        />
        <DatePicker.RangePicker
          allowClear
          onChange={(values) => {
            // RangePicker 的 values 类型为 [Dayjs|null, Dayjs|null] | null，
            // 只有两端都选中时才构成有效区间
            const next: [Dayjs, Dayjs] | null =
              values && values[0] && values[1] ? [values[0], values[1]] : null;
            setRange(next);
            load(1, pageSize, scene, next);
          }}
          value={range}
        />
        <Button
          icon={<ReloadOutlined aria-hidden="true" />}
          onClick={() => load(page, pageSize, scene, range)}
        >
          刷新
        </Button>
      </Space>

      {error && (
        <Alert
          action={
            // 403 是权限说明而非故障，重试不会改变结果，因此不提供重试按钮
            error.forbidden ? undefined : (
              <Button onClick={() => load(page, pageSize, scene, range)} size="small">
                重试
              </Button>
            )
          }
          description={error.message}
          message={error.forbidden ? "无权访问" : "加载失败"}
          showIcon
          type={error.forbidden ? "warning" : "error"}
        />
      )}

      <Space size="large" wrap>
        <Statistic title="调用总次数" value={stats?.totalCalls ?? 0} />
        <Statistic
          precision={2}
          suffix="%"
          title="成功率"
          // 契约里的 successRate 是 0~1 的小数，页面按百分比展示并保留两位
          value={(stats?.successRate ?? 0) * 100}
        />
        <Statistic suffix="ms" title="P95 延迟" value={stats?.p95LatencyMs ?? 0} />
        <Statistic title="Token 合计" value={stats?.totalTokens ?? 0} />
      </Space>

      <Table<LlmCallSummary>
        columns={columns}
        dataSource={data?.items ?? []}
        loading={loading}
        locale={{ emptyText: "当前筛选条件下暂无调用记录。" }}
        onChange={(pagination) =>
          load(
            pagination.current ?? 1,
            pagination.pageSize ?? DEFAULT_PAGE_SIZE,
            scene,
            range,
          )
        }
        pagination={{
          current: data?.page ?? page,
          pageSize: data?.size ?? pageSize,
          total: data?.total ?? 0,
          // 契约声明 size.maximum=50，页面不得给出超过该值的选项
          pageSizeOptions: [10, 20, 50],
          showQuickJumper: true,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`,
        }}
        rowKey="id"
        scroll={{ x: 1200 }}
      />
    </div>
  );
}
