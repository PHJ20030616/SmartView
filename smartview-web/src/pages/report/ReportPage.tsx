/**
 * 报告页面组件
 *
 * 展示面试复盘报告：
 * - 入参：?sessionId=（面试结束页带入，优先）、?reportId=（直查）；两者皆无 → 空态引导
 * - 状态机：loading（首拉）→ generating（轮询）｜success（展示）｜failed（可重试）｜error
 * - 报告状态：GENERATING 轮询（3s/最长 3 分钟），超时后停止自动轮询提示稍后刷新；
 *   FAILED 提供重试生成；网络/接口错误进入 error 可整页重载
 * - 安全约束：只展示契约白名单字段；不展示原始面试计划（stage_plan/current_stage 等）
 */
import {
  FileSearchOutlined,
  LoadingOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import {
  Alert,
  Button,
  Card,
  Collapse,
  Progress,
  Space,
  Spin,
  Tag,
  Typography,
} from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import type { components } from "../../api/generated/schema";
import {
  fetchReport,
  fetchReportBySession,
  ReportError,
  retryReport,
  toReportError,
  waitForReport,
} from "../../features/report";
import {
  ANSWER_TYPE_LABEL,
  READINESS_COLOR,
  READINESS_LABEL,
  ROLE_DIRECTION_LABEL,
} from "../../features/report";

type InterviewReport = components["schemas"]["InterviewReport"];

/** 页面状态机；generating.timedOut 标记轮询超时，停止自动轮询并提示稍后刷新 */
type PageState =
  | { phase: "loading" }
  | { phase: "generating"; reportId: string; timedOut?: boolean }
  | { phase: "success"; report: InterviewReport }
  | { phase: "failed"; reportId: string }
  | { phase: "error"; message: string };

/** 覆盖情况三阶段展示名（与契约 ReportCoverage 字段一一对应） */
const COVERAGE_ITEMS: Array<{
  key: keyof NonNullable<InterviewReport["coverage"]>;
  label: string;
}> = [
  { key: "basicCoverage", label: "基础题" },
  { key: "projectCoverage", label: "项目题" },
  { key: "scenarioCoverage", label: "场景题" },
];

export default function ReportPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get("sessionId") ?? undefined;
  const reportId = searchParams.get("reportId") ?? undefined;
  const hasTarget = Boolean(sessionId || reportId);

  const [state, setState] = useState<PageState>({ phase: "loading" });
  // 页面销毁时中止未完成请求/轮询
  const mountedRef = useRef(true);
  const controllerRef = useRef<AbortController | null>(null);
  // 重试请求进行中标记：防止连点并发触发多个 retry POST
  const [retrying, setRetrying] = useState(false);

  /**
   * 依据报告状态落入 generating / success / failed。
   * 边界处理：status 缺失/未知（后端枚举转换失败会返回 null）时直接进入 error 态，
   * 避免误判为生成中后白等轮询 3 分钟；generating 但缺少 reportId 同样回退 error。
   */
  const settleReport = useCallback((data: InterviewReport) => {
    if (data.status === "SUCCESS") {
      setState({ phase: "success", report: data });
      return;
    }
    if (data.status === "FAILED") {
      setState(
        data.id ? { phase: "failed", reportId: data.id } : { phase: "error", message: "报告状态异常" },
      );
      return;
    }
    if (data.status === "GENERATING") {
      setState(
        data.id
          ? { phase: "generating", reportId: data.id }
          : { phase: "error", message: "报告尚未开始生成" },
      );
      return;
    }
    setState({ phase: "error", message: "报告状态异常" });
  }, []);

  /** 首拉：sessionId 优先（面试结束页入口），其次 reportId 直查 */
  useEffect(() => {
    mountedRef.current = true;
    if (!hasTarget) {
      // 无目标参数：保持空态引导，不发起请求
      setState({ phase: "loading" });
      return;
    }
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const run = async () => {
      try {
        const initial = sessionId
          ? await fetchReportBySession(sessionId, controller.signal)
          : await fetchReport(reportId as string, controller.signal);
        if (!mountedRef.current || controller.signal.aborted) return;
        settleReport(initial);
      } catch (error) {
        if (!mountedRef.current || controller.signal.aborted) return;
        setState({
          phase: "error",
          message: toReportError(error, "报告加载失败").message,
        });
      } finally {
        if (controllerRef.current === controller) {
          controllerRef.current = null;
        }
      }
    };
    void run();
    return () => {
      mountedRef.current = false;
      controller.abort();
    };
  }, [sessionId, reportId, hasTarget, settleReport]);

  /** generating 阶段轮询直到终态；timedOut 后停止自动轮询，等待用户手动刷新 */
  useEffect(() => {
    if (state.phase !== "generating" || state.timedOut) {
      return;
    }
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const run = async () => {
      try {
        const current = await waitForReport(state.reportId, controller.signal);
        if (!mountedRef.current || controller.signal.aborted) return;
        settleReport(current);
      } catch (error) {
        if (!mountedRef.current || controller.signal.aborted) return;
        if (error instanceof ReportError && error.timeout) {
          // 超过 3 分钟仍未终态：停止自动轮询，提示稍后刷新查看
          setState({ phase: "generating", reportId: state.reportId, timedOut: true });
          return;
        }
        setState({
          phase: "error",
          message: toReportError(error, "报告生成状态查询失败").message,
        });
      }
    };
    void run();
    return () => {
      controller.abort();
    };
  }, [state, settleReport]);

  /** 失败重试：调 retry 接口置回 GENERATING 后重新轮询；retrying 防连点 */
  const handleRetry = useCallback(async () => {
    if (state.phase !== "failed" || retrying) return;
    setRetrying(true);
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    try {
      const retried = await retryReport(state.reportId, controller.signal);
      if (!mountedRef.current || controller.signal.aborted) return;
      settleReport(retried);
    } catch (error) {
      if (!mountedRef.current || controller.signal.aborted) return;
      setState({
        phase: "error",
        message: toReportError(error, "重试生成失败，请稍后再试").message,
      });
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null;
      }
      setRetrying(false);
    }
  }, [state, settleReport, retrying]);

  // ==================== 渲染 ====================

  /* 无目标参数：空态引导（保留原占位页体验） */
  if (!hasTarget) {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>复盘报告</Typography.Title>
          <Typography.Paragraph className="page-subtitle">
            完成一次模拟面试后，这里会展示准备度、风险点和学习建议。
          </Typography.Paragraph>
        </section>
        <Card className="report-empty-panel" bordered={false}>
          <FileSearchOutlined className="report-empty-icon" />
          <Typography.Title level={3}>暂无报告</Typography.Title>
          <Typography.Paragraph type="secondary">
            从面试结束页进入即可查看对应复盘报告。
          </Typography.Paragraph>
        </Card>
      </div>
    );
  }

  if (state.phase === "loading") {
    return <LoadPanel title="正在加载报告" />;
  }

  if (state.phase === "generating") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>复盘报告</Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Spin indicator={<LoadingOutlined className="state-panel-icon" spin />} />
              <Typography.Title className="state-panel-title" level={3}>报告生成中</Typography.Title>
              <Typography.Paragraph className="state-panel-description">
                {state.timedOut
                  ? "报告生成耗时较长，可稍后刷新页面查看，或先返回首页。"
                  : "正在结合答题表现生成复盘报告，通常需要数十秒，请稍候。"}
              </Typography.Paragraph>
              <Space style={{ marginTop: 20 }} size={12} wrap>
                {state.timedOut && (
                  <Button
                    icon={<ReloadOutlined />}
                    onClick={() => setState({ phase: "generating", reportId: state.reportId })}
                    type="primary"
                  >
                    重新查询
                  </Button>
                )}
                <Button onClick={() => navigate("/")}>返回首页</Button>
              </Space>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  if (state.phase === "error") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>复盘报告</Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Alert message="报告加载失败" description={state.message} showIcon type="error" />
              <Space style={{ marginTop: 20 }}>
                <Button onClick={() => navigate("/")}>返回首页</Button>
                <Button onClick={() => window.location.reload()} type="primary">重试</Button>
              </Space>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  if (state.phase === "failed") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>复盘报告</Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Alert
                message="报告生成失败"
                description="可重试生成，或稍后返回查看。"
                showIcon
                type="error"
              />
              <Space style={{ marginTop: 20 }} size={12} wrap>
                <Button
                  icon={<ReloadOutlined />}
                  loading={retrying}
                  onClick={() => void handleRetry()}
                  type="primary"
                >
                  重试生成
                </Button>
                <Button onClick={() => navigate("/")}>稍后查看</Button>
              </Space>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  // success
  const reportData = state.report;
  const directionLabel = reportData.roleDirection
    ? ROLE_DIRECTION_LABEL[reportData.roleDirection] ?? reportData.roleDirection
    : undefined;
  const readiness = reportData.readinessLevel;
  const readinessLabel = readiness ? READINESS_LABEL[readiness] ?? readiness : undefined;
  const readinessColor = readiness ? READINESS_COLOR[readiness] ?? "default" : undefined;
  // 参考答案按问题 ID 索引，逐题复盘时 O(1) 查找
  const referenceByQuestion = new Map(
    (reportData.referenceAnswers ?? []).map((item) => [item.questionId, item]),
  );
  const progressPercent = reportData.overallScore ?? 0;
  const roleFitPercent = reportData.roleFitScore ?? 0;

  return (
    <div className="page-stack">
      <section className="page-header">
        <Space align="center" size={14} wrap>
          <Typography.Title className="page-title" level={1}>复盘报告</Typography.Title>
          {directionLabel && <Tag color="processing">{directionLabel}</Tag>}
        </Space>
      </section>

      {/* 头部总览：综合得分 + 准备度 + 岗位匹配度 */}
      <div className="report-overview-grid">
        <Card bordered={false} className="report-score-card">
          <Typography.Text className="report-score-label">综合得分</Typography.Text>
          <Typography.Title className="report-score-value" level={1}>{progressPercent}</Typography.Title>
          <Progress percent={progressPercent} showInfo={false} strokeColor="#0f9f94" />
        </Card>
        <Card bordered={false} className="report-score-card">
          <Typography.Text className="report-score-label">面试准备度</Typography.Text>
          <div className="report-readiness">
            {readinessLabel && <Tag color={readinessColor}>{readinessLabel}</Tag>}
          </div>
          <Typography.Text type="secondary">{reportData.summary}</Typography.Text>
        </Card>
        <Card bordered={false} className="report-score-card">
          <Typography.Text className="report-score-label">岗位匹配度</Typography.Text>
          <Typography.Title className="report-score-value" level={1}>{roleFitPercent}</Typography.Title>
          <Progress percent={roleFitPercent} showInfo={false} strokeColor="#12a69d" />
        </Card>
      </div>

      {/* 覆盖情况：三阶段覆盖条（0~1 比例转百分比） */}
      <Card bordered={false} className="report-section">
        <Typography.Title className="detail-section-title" level={4}>知识点覆盖</Typography.Title>
        <div className="report-coverage">
          {COVERAGE_ITEMS.map(({ key, label }) => {
            const ratio = (reportData.coverage?.[key] as number | undefined) ?? 0;
            return (
              <div className="report-coverage-item" key={key}>
                <span className="report-coverage-label">{label}</span>
                <Progress percent={Math.round(ratio * 100)} size="small" />
              </div>
            );
          })}
        </div>
      </Card>

      {/* 四象限：优势 / 薄弱 / 风险 / 建议 */}
      <div className="report-quad-grid">
        <Card bordered={false} className="report-quad-card">
          <Typography.Text className="report-quad-title is-good">优势</Typography.Text>
          <ListText items={reportData.strengths ?? []} />
        </Card>
        <Card bordered={false} className="report-quad-card">
          <Typography.Text className="report-quad-title is-warning">薄弱点</Typography.Text>
          <ListText items={reportData.weaknesses ?? []} />
        </Card>
        <Card bordered={false} className="report-quad-card">
          <Typography.Text className="report-quad-title is-danger">风险点</Typography.Text>
          <ListText items={reportData.riskPoints ?? []} />
        </Card>
        <Card bordered={false} className="report-quad-card">
          <Typography.Text className="report-quad-title">学习建议</Typography.Text>
          <ul className="report-suggestion-list">
            {(reportData.suggestions ?? []).map((item, index) => (
              <li key={index}>
                <strong>{item.topic}</strong>
                {item.reason && <span>：{item.reason}</span>}
                {(item.resources?.length ?? 0) > 0 && (
                  <div className="report-suggestion-resources">
                    {item.resources?.map((res) => (
                      <Tag key={res}>{res}</Tag>
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        </Card>
      </div>

      {/* 逐题复盘：题目 + 我的回答 + 评估 + 折叠参考答案 */}
      <Card bordered={false} className="report-section">
        <Typography.Title className="detail-section-title" level={4}>逐题复盘</Typography.Title>
        {(reportData.answers ?? []).length === 0 ? (
          <Typography.Paragraph type="secondary">暂无已答题目。</Typography.Paragraph>
        ) : (
          <div className="report-review-list">
            {(reportData.answers ?? []).map((item) => {
              const reference = referenceByQuestion.get(item.question.id);
              const levelColor = levelTagColor(item.evaluation?.level);
              return (
                <Card key={item.question.id} bordered={false} className="report-review-card">
                  <Typography.Paragraph className="report-review-question">
                    <Tag color="default">#{item.question.questionOrder}</Tag>
                    {item.question.questionText}
                  </Typography.Paragraph>
                  <Typography.Paragraph className="report-review-answer">
                    <strong>我的回答：</strong>
                    {item.answerText}
                  </Typography.Paragraph>
                  {item.evaluation && (
                    <div className="report-review-evaluation">
                      <Space size={8} wrap>
                        <Tag color={levelColor}>
                          得分 {item.evaluation.score} · {item.evaluation.level}
                        </Tag>
                        {item.evaluation.evaluationText && (
                          <Typography.Text type="secondary">
                            {item.evaluation.evaluationText}
                          </Typography.Text>
                        )}
                      </Space>
                    </div>
                  )}
                  {reference && (
                    <Collapse
                      className="report-reference-collapse"
                      items={[
                        {
                          key: reference.questionId,
                          label: `参考答案 · ${ANSWER_TYPE_LABEL[reference.answerType] ?? reference.answerType}`,
                          children: (
                            <div>
                              <Typography.Paragraph>{reference.referenceContent}</Typography.Paragraph>
                              {(reference.keyPoints?.length ?? 0) > 0 && (
                                <div className="report-reference-points">
                                  {(reference.keyPoints ?? []).map((point) => (
                                    <Tag key={point}>{point}</Tag>
                                  ))}
                                </div>
                              )}
                              {/* 场景题权衡点：每个维度列出可选方案 */}
                              {(reference.tradeoffs?.length ?? 0) > 0 && (
                                <div className="report-reference-points">
                                  {(reference.tradeoffs ?? []).map((tradeoff, index) => (
                                    <div className="report-tradeoff-item" key={index}>
                                      {tradeoff.aspect && <Tag color="blue">{tradeoff.aspect}</Tag>}
                                      {(tradeoff.options ?? []).map((option) => (
                                        <Tag key={option}>{option}</Tag>
                                      ))}
                                    </div>
                                  ))}
                                </div>
                              )}
                            </div>
                          ),
                        },
                      ]}
                    />
                  )}
                </Card>
              );
            })}
          </div>
        )}
      </Card>
    </div>
  );
}

/** 加载状态面板 */
function LoadPanel({ title }: { title: string }) {
  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>复盘报告</Typography.Title>
      </section>
      <Card bordered={false}>
        <div className="state-panel">
          <div className="state-panel-content">
            <Spin indicator={<LoadingOutlined className="state-panel-icon" spin />} />
            <Typography.Title className="state-panel-title" level={3}>{title}</Typography.Title>
          </div>
        </div>
      </Card>
    </div>
  );
}

/** 列表渲染（优势/薄弱/风险通用） */
function ListText({ items }: { items: string[] }) {
  if (items.length === 0) {
    return <Typography.Paragraph type="secondary">暂无</Typography.Paragraph>;
  }
  return (
    <ul className="report-quad-list">
      {items.map((item, index) => (
        <li key={index}>{item}</li>
      ))}
    </ul>
  );
}

/** 评估等级 → 标签颜色（与面试会话页保持一致） */
function levelTagColor(level: string | undefined): string | undefined {
  if (level === "GOOD") return "success";
  if (level === "WEAK") return "error";
  return "warning";
}
