/**
 * 历史简历页面（Task 7.1 / Task 7.2）
 *
 * 展示当前用户上传过的全部简历文件历史（分页）：
 * - 文件名称、解析状态、大小、上传时间
 * - 解析成功且已生成画像的简历可进入画像确认页（查看画像）
 * - 解析失败的简历展示失败原因
 * - 可删除简历（软删除）：删除后列表不再展示，MinIO 文件与 Chroma 向量
 *   由后台 CLEANUP 任务异步清理
 * - 已软删除的简历由后端查询层自动过滤，不会出现在列表中
 *
 * 数据来源：GET /api/resumes?page=&size=（后端只返回当前用户数据）
 */
import {
  ArrowLeftOutlined,
  DeleteOutlined,
  FilePdfOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import {
  Alert,
  Button,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { deleteResumeApi, getResumeHistoryApi } from "../../features/resume";
import type { ResumeFile, ResumeFilePage } from "../../features/resume";

/** 页面默认分页大小（与后端契约默认值一致） */
const DEFAULT_PAGE_SIZE = 10;

/** 解析状态 → 展示标签 */
const PARSE_STATUS_TAG: Record<ResumeFile["parseStatus"], { label: string; color: string }> = {
  PENDING: { label: "待解析", color: "default" },
  PROCESSING: { label: "解析中", color: "processing" },
  SUCCESS: { label: "解析成功", color: "success" },
  FAILED: { label: "解析失败", color: "error" },
};

/** 文件大小人类可读展示（字节 → KB/MB） */
function formatFileSize(bytes?: number | null): string {
  if (bytes == null) return "-";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export default function ResumeHistoryPage() {
  const navigate = useNavigate();
  // 分页状态：切页/改每页条数时重新拉取
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [data, setData] = useState<ResumeFilePage | null>(null);
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
      const result = await getResumeHistoryApi(targetPage, targetSize, controller.signal);
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
        setError(err instanceof Error ? err.message : "历史简历加载失败，请重试");
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

  /**
   * 删除简历（软删除，Task 7.2）。
   * 删除成功后刷新当前页；若当前页因删除而空且非第一页，load 会自动回退到最后一页。
   */
  const handleDelete = async (record: ResumeFile) => {
    try {
      await deleteResumeApi(String(record.id));
      message.success("简历已删除");
      void load(page, pageSize);
    } catch (err) {
      message.error(err instanceof Error ? err.message : "删除失败，请重试");
    }
  };

  const columns: ColumnsType<ResumeFile> = [
    {
      title: "文件名",
      dataIndex: "originalFilename",
      key: "originalFilename",
      ellipsis: true,
      render: (name: string, record) => (
        <Space size={8}>
          <FilePdfOutlined aria-hidden="true" />
          <Typography.Text ellipsis={{ tooltip: name }}>{name}</Typography.Text>
          {/* 解析失败时直接展示原因，避免用户困惑 */}
          {record.parseStatus === "FAILED" && record.errorMessage && (
            <Tooltip title={record.errorMessage}>
              <Typography.Text type="danger">失败原因</Typography.Text>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: "解析状态",
      dataIndex: "parseStatus",
      key: "parseStatus",
      width: 120,
      render: (status: ResumeFile["parseStatus"]) => {
        const tag = PARSE_STATUS_TAG[status] ?? { label: status, color: "default" };
        return <Tag color={tag.color}>{tag.label}</Tag>;
      },
    },
    {
      title: "文件大小",
      dataIndex: "fileSize",
      key: "fileSize",
      width: 120,
      render: formatFileSize,
    },
    {
      title: "上传时间",
      dataIndex: "uploadedAt",
      key: "uploadedAt",
      width: 200,
      render: (time?: string | null) =>
        time ? new Date(time).toLocaleString("zh-CN") : "-",
    },
    {
      title: "操作",
      key: "actions",
      width: 180,
      render: (_, record) => (
        <Space size={4}>
          {record.parseStatus === "SUCCESS" && record.profileId ? (
            <Button
              size="small"
              type="link"
              onClick={() => navigate(`/resume/confirm/${record.profileId}`)}
            >
              查看画像
            </Button>
          ) : (
            <Typography.Text type="secondary">-</Typography.Text>
          )}
          {/* 删除为软删除：确认后调用后端删除接口，MinIO/Chroma 由后台清理任务异步处理 */}
          <Popconfirm
            title="删除该简历？"
            description="删除后不可恢复，历史列表中不再展示。"
            okText="确认删除"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            onConfirm={() => void handleDelete(record)}
          >
            <Button
              size="small"
              type="link"
              danger
              icon={<DeleteOutlined aria-hidden="true" />}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div className="page-stack">
      <section className="page-header">
        <Space align="center" size={12}>
          <Button
            aria-label="返回简历上传页"
            icon={<ArrowLeftOutlined aria-hidden="true" />}
            onClick={() => navigate("/resume")}
            type="text"
          />
          <Typography.Title className="page-title" level={1}>
            历史简历
          </Typography.Title>
        </Space>
        <Typography.Paragraph className="page-subtitle">
          查看已上传的简历及解析状态，解析成功的简历可进入画像确认页。
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

      <Table<ResumeFile>
        columns={columns}
        dataSource={data?.items ?? []}
        loading={loading}
        locale={{ emptyText: "暂无历史简历，去上传你的第一份简历吧" }}
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
        scroll={{ x: 720 }}
      />
    </div>
  );
}
