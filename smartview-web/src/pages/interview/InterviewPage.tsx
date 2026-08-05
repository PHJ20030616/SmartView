/**
 * 面试页面组件
 *
 * 在开始模拟面试前，用户需要：
 * - 选择面试方向（Java 后端 / Agent 开发）
 * - 触发该方向的画像分析并等待完成（最多 60 秒）
 * - 分析成功后点击"开始面试"进入会话（v0.5 实现）
 *
 * 画像分析失败时允许重试，未成功前不允许开始面试。
 */
import {
  ApiOutlined,
  CheckCircleFilled,
  CodeOutlined,
  LoadingOutlined,
  ReloadOutlined,
  RobotOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import {
  Alert,
  Button,
  Card,
  Space,
  Spin,
  Tag,
  Typography,
} from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import {
  ProfileAnalysisError,
  extractErrorMessage,
  retryProfileAnalysis,
  startProfileAnalysis,
  waitForProfileAnalysis,
} from "../../features/resume";
import type {
  ProfileAnalysisStatus,
  RoleDirection,
} from "../../features/resume";

/** 页面状态机 */
type PageState =
  | { phase: "loading" }
  | { phase: "missing-profile" }
  | { phase: "selecting" }
  | {
      phase: "analyzing";
      direction: RoleDirection;
      status?: ProfileAnalysisStatus;
    }
  | { phase: "success"; direction: RoleDirection; status: ProfileAnalysisStatus }
  | {
      phase: "failed";
      direction: RoleDirection;
      status?: ProfileAnalysisStatus;
      errorMessage: string;
    };

/** 面试方向选项 */
const DIRECTIONS: Array<{
  value: RoleDirection;
  label: string;
  description: string;
}> = [
  {
    value: "JAVA_BACKEND",
    label: "Java 后端",
    description:
      "考察并发、JVM、Spring 生态、数据库与分布式系统设计等后端核心能力。",
  },
  {
    value: "AGENT_DEVELOPMENT",
    label: "Agent 开发",
    description:
      "考察 LangGraph、RAG、多智能体编排、工具调用等 Agent 应用开发能力。",
  },
];

export default function InterviewPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const profileId = searchParams.get("profileId") ?? undefined;
  const [state, setState] = useState<PageState>({ phase: "loading" });
  // 组件卸载或重新选择方向时取消仍在进行的轮询请求。
  const mountedRef = useRef(true);
  const controllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    // StrictMode 开发模式会额外执行一次 setup -> cleanup -> setup，
    // 必须在 setup 时重置卸载标记，避免清理逻辑把后续异步回调全部拦截。
    mountedRef.current = true;
    if (!profileId) {
      setState({ phase: "missing-profile" });
      return;
    }
    setState({ phase: "selecting" });
    return () => {
      mountedRef.current = false;
      controllerRef.current?.abort();
    };
  }, [profileId]);

  /** 选择方向后：触发画像分析并轮询直至成功/失败/超时。 */
  const analyzeDirection = useCallback(
    async (direction: RoleDirection) => {
      if (!profileId) return;
      controllerRef.current?.abort();
      const controller = new AbortController();
      controllerRef.current = controller;

      setState({ phase: "analyzing", direction });
      try {
        // startProfileAnalysis 幂等：可能直接返回 SUCCESS / 进行中 / FAILED。
        const initial = await startProfileAnalysis(
          profileId,
          direction,
          controller.signal,
        );
        if (!mountedRef.current || controller.signal.aborted) return;
        if (!handleTerminalStatus(direction, initial)) return;

        // 进行中：轮询（最多 60 秒），期间实时回填状态供页面展示。
        const final = await waitForProfileAnalysis(
          profileId,
          direction,
          (next) => {
            if (!mountedRef.current || controller.signal.aborted) return;
            setState({ phase: "analyzing", direction, status: next });
          },
          controller.signal,
        );
        if (!mountedRef.current || controller.signal.aborted) return;
        setState({ phase: "success", direction, status: final });
      } catch (error: unknown) {
        if (!mountedRef.current || controller.signal.aborted) return;
        if (error instanceof ProfileAnalysisError) {
          setState({
            phase: "failed",
            direction,
            status: error.status,
            errorMessage: error.message,
          });
        } else {
          setState({
            phase: "failed",
            direction,
            errorMessage: extractErrorMessage(error, "画像分析失败，请重试"),
          });
        }
      } finally {
        if (controllerRef.current === controller) {
          controllerRef.current = null;
        }
      }
    },
    [profileId],
  );

  /** 分析失败后的手工重试：先重建任务，再进行相同的轮询流程。 */
  const handleRetry = useCallback(async () => {
    if (!profileId || state.phase !== "failed") return;
    const direction = state.direction;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;

    setState({ phase: "analyzing", direction });
    try {
      const created = await retryProfileAnalysis(
        profileId,
        direction,
        controller.signal,
      );
      if (!mountedRef.current || controller.signal.aborted) return;
      if (!handleTerminalStatus(direction, created)) return;

      const final = await waitForProfileAnalysis(
        profileId,
        direction,
        (next) => {
          if (!mountedRef.current || controller.signal.aborted) return;
          setState({ phase: "analyzing", direction, status: next });
        },
        controller.signal,
      );
      if (!mountedRef.current || controller.signal.aborted) return;
      setState({ phase: "success", direction, status: final });
    } catch (error: unknown) {
      if (!mountedRef.current || controller.signal.aborted) return;
      if (error instanceof ProfileAnalysisError) {
        setState({
          phase: "failed",
          direction,
          status: error.status,
          errorMessage: error.message,
        });
      } else {
        setState({
          phase: "failed",
          direction,
          errorMessage: extractErrorMessage(
            error,
            "画像分析重试失败，请稍后再试",
          ),
        });
      }
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null;
      }
    }
  }, [profileId, state]);

  /**
   * 校验触发接口直接返回的终态：SUCCESS / FAILED 时直接落定并返回 false，
   * 表示无需继续轮询；进行中状态返回 true 表示继续等待。
   */
  const handleTerminalStatus = (
    direction: RoleDirection,
    status: ProfileAnalysisStatus,
  ): boolean => {
    if (status.status === "SUCCESS") {
      setState({ phase: "success", direction, status });
      return false;
    }
    if (status.status === "FAILED") {
      setState({
        phase: "failed",
        direction,
        status,
        errorMessage: status.errorMessage || "画像分析失败，请重试",
      });
      return false;
    }
    return true;
  };

  // ==================== 渲染 ====================

  /* 加载中 */
  if (state.phase === "loading") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>
            模拟面试
          </Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Spin
                indicator={<LoadingOutlined className="state-panel-icon" spin />}
              />
              <Typography.Title className="state-panel-title" level={3}>
                正在准备面试
              </Typography.Title>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  /* 缺少画像 ID */
  if (state.phase === "missing-profile") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>
            模拟面试
          </Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <ApiOutlined className="state-panel-icon" />
              <Typography.Title className="state-panel-title" level={3}>
                缺少简历画像
              </Typography.Title>
              <Typography.Paragraph className="state-panel-description">
                请先完成简历上传、确认与向量入库，再从确认页进入面试方向选择。
              </Typography.Paragraph>
              <Button onClick={() => navigate("/resume")} type="primary">
                去上传简历
              </Button>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  /* 方向选择 */
  if (state.phase === "selecting") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>
            模拟面试
          </Typography.Title>
          <Typography.Paragraph className="page-subtitle">
            简历检索索引已准备完成，请选择面试方向。系统将为该方向生成画像分析，
            用于后续出题与追问。
          </Typography.Paragraph>
        </section>

        <div className="direction-grid">
          {DIRECTIONS.map((item) => (
            <Card
              aria-label={`选择 ${item.label} 方向`}
              className="direction-card"
              hoverable
              key={item.value}
              onClick={() => void analyzeDirection(item.value)}
              onKeyDown={(event: React.KeyboardEvent) => {
                // 键盘可达性：与首页入口卡片保持一致，Enter/Space 触发选择。
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  void analyzeDirection(item.value);
                }
              }}
              role="button"
              tabIndex={0}
            >
              <div className="direction-card-icon">
                {item.value === "JAVA_BACKEND" ? (
                  <CodeOutlined aria-hidden="true" />
                ) : (
                  <RobotOutlined aria-hidden="true" />
                )}
              </div>
              <Typography.Title className="direction-card-title" level={3}>
                {item.label}
              </Typography.Title>
              <Typography.Paragraph className="direction-card-desc">
                {item.description}
              </Typography.Paragraph>
              <div className="direction-card-action">
                <ThunderboltOutlined aria-hidden="true" />
                生成该方向画像分析
              </div>
            </Card>
          ))}
        </div>
      </div>
    );
  }

  /* 分析中 */
  if (state.phase === "analyzing") {
    const directionLabel =
      DIRECTIONS.find((item) => item.value === state.direction)?.label ??
      state.direction;
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>
            模拟面试
          </Typography.Title>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Spin
                indicator={<LoadingOutlined className="state-panel-icon" spin />}
              />
              <Typography.Title className="state-panel-title" level={3}>
                正在生成 {directionLabel} 画像分析
              </Typography.Title>
              <Typography.Paragraph className="state-panel-description">
                系统正在结合简历向量与知识库生成该方向的分析材料，
                通常需要数十秒，请稍候（最多等待 60 秒）。
              </Typography.Paragraph>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  /* 分析成功 */
  if (state.phase === "success") {
    const directionLabel =
      DIRECTIONS.find((item) => item.value === state.direction)?.label ??
      state.direction;
    return (
      <div className="page-stack">
        <section className="page-header">
          <Space align="center" size={14}>
            <Typography.Title className="page-title" level={1}>
              模拟面试
            </Typography.Title>
            <Tag color="success">{directionLabel}</Tag>
          </Space>
        </section>
        <Card bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <CheckCircleFilled className="state-panel-icon is-success" />
              <Typography.Title className="state-panel-title" level={3}>
                {directionLabel} 画像分析已完成
              </Typography.Title>
              <Typography.Paragraph className="state-panel-description">
                系统已准备好该方向的面试材料，可以开始模拟面试了。
              </Typography.Paragraph>
              <Space size={12} wrap>
                <Button
                  onClick={() =>
                    navigate(
                      `/interview/session?profileId=${encodeURIComponent(
                        profileId as string,
                      )}&roleDirection=${state.direction}`,
                    )
                  }
                  type="primary"
                >
                  开始面试
                </Button>
                <Button onClick={() => setState({ phase: "selecting" })}>
                  重新选择方向
                </Button>
              </Space>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  /* 分析失败 */
  const failedDirectionLabel =
    DIRECTIONS.find((item) => item.value === state.direction)?.label ??
    state.direction;
  return (
    <div className="page-stack">
      <section className="page-header">
        <Space align="center" size={14}>
          <Typography.Title className="page-title" level={1}>
            模拟面试
          </Typography.Title>
          <Tag color="error">{failedDirectionLabel}</Tag>
        </Space>
      </section>
      <Card bordered={false}>
        <div className="state-panel">
          <div className="state-panel-content">
            <Alert
              description={state.errorMessage}
              message="画像分析未完成"
              showIcon
              type="error"
            />
            <Space
              style={{ marginTop: 20 }}
              size={12}
              wrap
            >
              <Button
                icon={<ReloadOutlined />}
                onClick={() => void handleRetry()}
                type="primary"
              >
                重试画像分析
              </Button>
              <Button onClick={() => setState({ phase: "selecting" })}>
                重新选择方向
              </Button>
            </Space>
          </div>
        </div>
      </Card>
    </div>
  );
}
