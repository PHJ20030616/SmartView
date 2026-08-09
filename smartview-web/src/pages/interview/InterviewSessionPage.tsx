/**
 * 面试会话页面
 *
 * 展示当前问题与历史问答，支持文本回答、提前结束；页面刷新后按 sessionId 恢复会话。
 * 会话状态机：loading（恢复/创建）→ active（回答中）/ ended（已结束）/ error（加载失败）。
 * 安全约束：只读取契约 DTO 白名单字段，不展示 current_stage / 阶段计划等内部字段。
 */
import { CheckCircleFilled, LoadingOutlined } from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  Input,
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
  createSession,
  finishSession,
  isConflictError,
  restoreSession,
  submitAnswer,
  toInterviewError,
} from "../../features/interview";

type InterviewSession = components["schemas"]["InterviewSession"];
type RoleDirection = components["schemas"]["CreateInterviewSessionRequest"]["roleDirection"];
type AnswerHistoryItem = components["schemas"]["AnswerHistoryItem"];

/** 页面状态机 */
type PageState =
  | { phase: "loading" }
  | { phase: "active"; session: InterviewSession }
  | { phase: "ended"; session: InterviewSession }
  | { phase: "error"; message: string };

/** 方向中文名（与入口页保持一致） */
const DIRECTION_LABEL: Record<RoleDirection, string> = {
  JAVA_BACKEND: "Java 后端",
  AGENT_DEVELOPMENT: "Agent 开发",
};

/** 评估等级 → 标签颜色 */
const LEVEL_COLOR: Record<string, string> = {
  GOOD: "success",
  NORMAL: "warning",
  WEAK: "error",
};

export default function InterviewSessionPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get("sessionId") ?? undefined;
  const profileId = searchParams.get("profileId") ?? undefined;
  const roleDirection = searchParams.get("roleDirection") as RoleDirection | null;
  const { modal } = App.useApp();

  const [state, setState] = useState<PageState>({ phase: "loading" });
  const [answerText, setAnswerText] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [finishing, setFinishing] = useState(false);
  const mountedRef = useRef(true);
  // 记录当前题目展示时刻，用于统计作答耗时；对账/推进后重置
  const questionShownAtRef = useRef(Date.now());
  // 刚创建会话并写回 URL 时，缓存会话避免 effect 重跑再发一次恢复请求
  const sessionCacheRef = useRef<InterviewSession | null>(null);

  /** 依据会话状态落入 active/ended，恢复/对账共用入口 */
  const applySession = useCallback((session: InterviewSession) => {
    if (session.status === "IN_PROGRESS") {
      setState({ phase: "active", session });
    } else {
      setState({ phase: "ended", session });
    }
    setAnswerText("");
    setSubmitError(null);
    questionShownAtRef.current = Date.now();
  }, []);

  /** 首次进入：有 sessionId 则恢复；否则创建会话并把 sessionId 写回 URL（刷新可恢复） */
  useEffect(() => {
    mountedRef.current = true;
    const controller = new AbortController();
    const run = async () => {
      try {
        let session: InterviewSession;
        if (sessionId && sessionCacheRef.current?.id === sessionId) {
          session = sessionCacheRef.current;
        } else if (sessionId) {
          session = await restoreSession(sessionId, controller.signal);
        } else if (profileId && roleDirection) {
          session = await createSession(profileId, roleDirection, controller.signal);
          sessionCacheRef.current = session;
          navigate(
            `/interview/session?sessionId=${encodeURIComponent(session.id)}`,
            { replace: true },
          );
        } else {
          setState({ phase: "error", message: "缺少会话参数，无法开始面试" });
          return;
        }
        if (mountedRef.current && !controller.signal.aborted) {
          applySession(session);
        }
      } catch (error) {
        if (mountedRef.current && !controller.signal.aborted) {
          setState({ phase: "error", message: toInterviewError(error, "面试加载失败").message });
        }
      }
    };
    void run();
    return () => {
      mountedRef.current = false;
      controller.abort();
    };
  }, [sessionId, profileId, roleDirection, navigate, applySession]);

  /** 提交回答：成功追加历史并推进，结束则进入 ended；409 表示会话已变化，对账刷新 */
  const handleSubmit = async () => {
    const trimmed = answerText.trim();
    const session = state.phase === "active" ? state.session : null;
    if (!trimmed || submitting || !session?.currentQuestion) return;

    const durationSeconds = Math.max(
      1,
      Math.round((Date.now() - questionShownAtRef.current) / 1000),
    );
    setSubmitting(true);
    setSubmitError(null);
    try {
      const result = await submitAnswer(
        session.id,
        session.currentQuestion,
        trimmed,
        durationSeconds,
      );
      if (!mountedRef.current) return;
      const answered: AnswerHistoryItem = {
        question: session.currentQuestion,
        answerText: trimmed,
        durationSeconds,
        submittedAt: new Date().toISOString(),
        evaluation: result.evaluation,
      };
      const history = [...(session.answers ?? []), answered];
      const nextCount = (session.questionCount ?? 0) + 1;
      if (result.nextQuestion && result.sessionStatus === "IN_PROGRESS") {
        setState({
          phase: "active",
          session: {
            ...session,
            answers: history,
            currentQuestion: result.nextQuestion,
            questionCount: nextCount,
          },
        });
        questionShownAtRef.current = Date.now();
      } else {
        // 会话结束（自然完成 / 进入报告生成）
        setState({
          phase: "ended",
          session: {
            ...session,
            answers: history,
            questionCount: nextCount,
            status: result.sessionStatus ?? "COMPLETED",
          },
        });
      }
      setAnswerText("");
    } catch (error) {
      if (!mountedRef.current) return;
      if (isConflictError(error)) {
        // 题目已被其他窗口提交或会话已推进：以服务端为准对账刷新
        try {
          const fresh = await restoreSession(session.id);
          if (mountedRef.current) applySession(fresh);
        } catch {
          if (mountedRef.current) {
            setState({ phase: "error", message: "会话状态已变化，刷新失败" });
          }
        }
      } else {
        setSubmitError(toInterviewError(error, "提交失败，请重试").message);
      }
    } finally {
      setSubmitting(false);
    }
  };

  /** 提前结束：二次确认后调后端置 COMPLETED */
  const handleFinish = () => {
    const session = state.phase === "active" ? state.session : null;
    if (!session || finishing) return;
    modal.confirm({
      title: "提前结束面试？",
      content: "已完成的回答会保留，结束后可随时开始新的面试。",
      okText: "结束面试",
      cancelText: "继续回答",
      okButtonProps: { danger: true },
      onOk: async () => {
        setFinishing(true);
        try {
          const ended = await finishSession(session.id);
          if (mountedRef.current) applySession(ended);
        } catch (error) {
          if (!mountedRef.current) return;
          if (isConflictError(error)) {
            try {
              const fresh = await restoreSession(session.id);
              if (mountedRef.current) applySession(fresh);
            } catch {
              if (mountedRef.current) {
                setState({ phase: "error", message: "会话状态已变化，刷新失败" });
              }
            }
          } else {
            modal.error({
              title: "结束失败",
              content: toInterviewError(error, "结束失败，请重试").message,
            });
          }
        } finally {
          setFinishing(false);
        }
      },
    });
  };

  // ==================== 渲染 ====================

  if (state.phase === "loading") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>模拟面试</Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Spin indicator={<LoadingOutlined className="state-panel-icon" spin />} />
              <Typography.Title className="state-panel-title" level={3}>正在加载面试</Typography.Title>
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
          <Typography.Title className="page-title" level={1}>模拟面试</Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Alert message="面试加载失败" description={state.message} showIcon type="error" />
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

  if (state.phase === "ended") {
    const ended = state.session;
    const directionLabel = DIRECTION_LABEL[ended.roleDirection] ?? ended.roleDirection;
    return (
      <div className="page-stack">
        <section className="page-header">
          <Space align="center" size={14}>
            <Typography.Title className="page-title" level={1}>模拟面试</Typography.Title>
            <Tag color="default">{directionLabel}</Tag>
          </Space>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <CheckCircleFilled className="state-panel-icon is-success" />
              <Typography.Title className="state-panel-title" level={3}>面试已结束</Typography.Title>
              <Typography.Paragraph className="state-panel-description">
                共完成 {ended.questionCount ?? 0} 题，已回答内容与评估均已保留。
                {ended.status === "COMPLETED" ? " 报告生成功能将在后续版本开放。" : ""}
              </Typography.Paragraph>
              <Space size={12} wrap>
                <Button type="primary" onClick={() => navigate("/")}>返回首页</Button>
                <Button
                  onClick={() =>
                    navigate(`/interview?profileId=${encodeURIComponent(ended.resumeProfileId)}`)
                  }
                >
                  再来一次
                </Button>
              </Space>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  // active
  const session = state.session;
  const directionLabel = DIRECTION_LABEL[session.roleDirection] ?? session.roleDirection;
  const history = session.answers ?? [];
  const progressPercent = session.expectedMaxQuestions
    ? Math.min(100, Math.round(((session.questionCount ?? 0) / session.expectedMaxQuestions) * 100))
    : 0;

  return (
    <div className="page-stack">
      <section className="page-header">
        <Space align="center" size={14} wrap>
          <Typography.Title className="page-title" level={1}>模拟面试</Typography.Title>
          <Tag color="processing">{directionLabel}</Tag>
          <Typography.Text className="session-progress-text">
            已完成 {session.questionCount ?? 0} 题 · 预计 {session.expectedMinQuestions ?? 0}~
            {session.expectedMaxQuestions ?? 0} 题
          </Typography.Text>
          <Button danger loading={finishing} onClick={handleFinish}>结束面试</Button>
        </Space>
      </section>

      <Card bordered={false}>
        <Progress percent={progressPercent} showInfo={false} size="small" />
      </Card>

      {/* 历史问答（仅已回答题目） */}
      {history.length > 0 && (
        <section className="session-history" aria-label="历史问答">
          {history.map((item) => (
            <Card className="qa-card" key={item.question.id} bordered={false}>
              <Typography.Paragraph className="qa-question">
                <Tag color="default">#{item.question.questionOrder}</Tag>
                {item.question.questionText}
              </Typography.Paragraph>
              <Typography.Paragraph className="qa-answer">
                <strong>我的回答：</strong>
                {item.answerText}
              </Typography.Paragraph>
              {item.evaluation && (
                <div className="qa-evaluation">
                  <Space size={8} wrap>
                    <Tag color={LEVEL_COLOR[item.evaluation.level] ?? "default"}>
                      得分 {item.evaluation.score} · {item.evaluation.level}
                    </Tag>
                    {item.evaluation.evaluationText && (
                      <Typography.Text type="secondary">{item.evaluation.evaluationText}</Typography.Text>
                    )}
                  </Space>
                </div>
              )}
            </Card>
          ))}
        </section>
      )}

      {/* 当前问题 + 回答输入 */}
      <Card bordered={false}>
        <Typography.Title level={4}>当前问题</Typography.Title>
        <Typography.Paragraph className="session-current-question">
          {session.currentQuestion?.questionText ?? "面试已无待答问题"}
        </Typography.Paragraph>
        <Input.TextArea
          aria-label="回答内容"
          autoSize={{ minRows: 4, maxRows: 10 }}
          disabled={submitting || !session.currentQuestion}
          onChange={(event) => setAnswerText(event.target.value)}
          placeholder="在这里输入你的回答…"
          value={answerText}
        />
        {submitError && (
          <Alert style={{ marginTop: 12 }} message={submitError} showIcon type="error" />
        )}
        <Space style={{ marginTop: 16 }}>
          <Button
            disabled={!answerText.trim() || !session.currentQuestion}
            loading={submitting}
            onClick={() => void handleSubmit()}
            type="primary"
          >
            提交回答
          </Button>
        </Space>
      </Card>
    </div>
  );
}
