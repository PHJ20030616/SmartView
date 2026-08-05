/**
 * 简历确认页面组件
 *
 * 展示 AI 解析的结构化简历数据，支持：
 * - 查看完整的结构化简历信息
 * - 轻量编辑关键字段（姓名、联系方式、技能）
 * - 确认解析结果并提交
 * - 已确认的画像不可再编辑
 */
import {
  CheckOutlined,
  EditOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  MailOutlined,
  PhoneOutlined,
  ReloadOutlined,
  SaveOutlined,
  UserOutlined,
} from "@ant-design/icons";
import {
  App,
  Button,
  Card,
  Descriptions,
  Divider,
  Empty,
  Form,
  Input,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";

import type {
  ResumeProfile,
  ResumeVectorizationStatus,
  UpdateResumeProfileRequest,
} from "../../features/resume";
import {
  ResumeVectorizationError,
  fetchResumeProfile,
  retryResumeVectorization,
  saveResumeProfile,
  submitResumeConfirmation,
  waitForResumeVectorization,
} from "../../features/resume";

/** 页面加载/操作状态 */
type PageState =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; profile: ResumeProfile; editing: boolean }
  | { phase: "saving"; previousProfile: ResumeProfile }
  | {
      phase: "confirmed";
      profile: ResumeProfile;
      vectorStatus: ResumeVectorizationStatus | null;
      vectorizing: boolean;
      vectorError?: string;
    };

export default function ResumeConfirmPage() {
  const { profileId } = useParams<{ profileId: string }>();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [state, setState] = useState<PageState>({ phase: "loading" });
  const [form] = Form.useForm();
  // 组件卸载标记，防止异步回调在已卸载组件上 setState
  const mountedRef = useRef(true);
  // 路由参数快速切换时，忽略旧画像请求的迟到响应。
  const loadRequestIdRef = useRef(0);
  // 统一取消当前画像请求，避免离开确认页后仍占用网络资源。
  const requestControllerRef = useRef<AbortController | null>(null);
  // 向量入库轮询使用独立控制器，确认页卸载或画像切换时立即停止轮询。
  const vectorizationControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    // 挂载时将 mountedRef 恢复为 true：StrictMode 开发模式会额外执行一次
    // setup -> cleanup -> setup，若 setup 不重置，cleanup 会把 mountedRef 置为
    // false，导致确认页的异步回调卸载守卫全部失效（页面会一直停在加载态）。
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      requestControllerRef.current?.abort();
      vectorizationControllerRef.current?.abort();
    };
  }, []);

  /** 开始新的画像请求，并取消同一页面上仍未完成的旧请求。 */
  const beginRequest = () => {
    requestControllerRef.current?.abort();
    const controller = new AbortController();
    requestControllerRef.current = controller;
    return controller;
  };

  /** 开始向量状态轮询，并将失败/超时保留为可重试的页面状态。 */
  const monitorVectorization = useCallback(async (currentProfileId: string) => {
    vectorizationControllerRef.current?.abort();
    const controller = new AbortController();
    vectorizationControllerRef.current = controller;

    setState((previous) =>
      previous.phase === "confirmed" &&
      previous.profile.id === currentProfileId
        ? { ...previous, vectorizing: true, vectorError: undefined }
        : previous,
    );

    try {
      const status = await waitForResumeVectorization(
        currentProfileId,
        (nextStatus) => {
          if (!mountedRef.current || controller.signal.aborted) return;
          setState((previous) =>
            previous.phase === "confirmed" &&
            previous.profile.id === currentProfileId
              ? {
                  ...previous,
                  vectorStatus: nextStatus,
                  vectorError: undefined,
                }
              : previous,
          );
        },
        controller.signal,
      );
      if (!mountedRef.current || controller.signal.aborted) return;
      setState((previous) =>
        previous.phase === "confirmed" &&
        previous.profile.id === currentProfileId
          ? { ...previous, vectorStatus: status, vectorizing: false }
          : previous,
      );
    } catch (error: unknown) {
      if (!mountedRef.current || controller.signal.aborted) return;
      const messageText =
        error instanceof ResumeVectorizationError
          ? error.message
          : error instanceof Error
            ? error.message
            : "查询简历向量入库状态失败，请重试";
      const latestStatus =
        error instanceof ResumeVectorizationError ? error.status : undefined;
      setState((previous) =>
        previous.phase === "confirmed" &&
        previous.profile.id === currentProfileId
          ? {
              ...previous,
              vectorStatus: latestStatus ?? previous.vectorStatus,
              vectorizing: false,
              vectorError: messageText,
            }
          : previous,
      );
    } finally {
      if (vectorizationControllerRef.current === controller) {
        vectorizationControllerRef.current = null;
      }
    }
  }, []);

  /** 加载画像数据 */
  const loadProfile = useCallback(async () => {
    if (!profileId) {
      if (!mountedRef.current) return;
      setState({ phase: "error", message: "缺少画像 ID 参数" });
      return;
    }
    const requestId = ++loadRequestIdRef.current;
    vectorizationControllerRef.current?.abort();
    setState({ phase: "loading" });
    const controller = beginRequest();
    try {
      const profile = await fetchResumeProfile(profileId, controller.signal);
      if (!mountedRef.current || requestId !== loadRequestIdRef.current) return;
      if (profile.confirmStatus === "CONFIRMED") {
        setState({
          phase: "confirmed",
          profile,
          vectorStatus: null,
          vectorizing: true,
        });
        // 已确认画像重新打开页面时也必须确认向量状态，不能仅凭 MySQL
        // 的 CONFIRMED 就展示面试入口。
        void monitorVectorization(profile.id);
      } else {
        setState({ phase: "ready", profile, editing: false });
      }
    } catch (error: unknown) {
      if (!mountedRef.current || requestId !== loadRequestIdRef.current) return;
      if (controller.signal.aborted) return;
      setState({
        phase: "error",
        message:
          error instanceof Error ? error.message : "加载简历画像失败",
      });
    } finally {
      if (requestControllerRef.current === controller) {
        requestControllerRef.current = null;
      }
    }
  }, [monitorVectorization, profileId]);

  useEffect(() => {
    void loadProfile();
  }, [loadProfile]);

  /** 进入编辑模式：回填表单 */
  const handleEdit = () => {
    if (state.phase !== "ready") return;
    const { profile } = state;
    const ci = profile.contactInfo as Record<string, string> | undefined;
    form.setFieldsValue({
      candidateName: profile.candidateName || "",
      phone: ci?.phone || "",
      email: ci?.email || "",
      wechat: ci?.wechat || "",
      skills: profile.skills || [],
    });
    setState({ phase: "ready", profile, editing: true });
  };

  /** 保存编辑 */
  const handleSave = async () => {
    if (state.phase !== "ready" || !profileId) return;
    let controller: AbortController | null = null;
    try {
      const values: Record<string, unknown> = await form.validateFields();
      setState({ phase: "saving", previousProfile: state.profile });
      controller = beginRequest();

      const updateData: UpdateResumeProfileRequest = {
        candidateName:
          typeof values.candidateName === "string"
            ? values.candidateName
            : undefined,
        contactInfo: {
          phone:
            typeof values.phone === "string" && values.phone.trim()
              ? values.phone
              : null,
          email:
            typeof values.email === "string" && values.email.trim()
              ? values.email
              : null,
          wechat:
            typeof values.wechat === "string" && values.wechat.trim()
              ? values.wechat
              : null,
        },
        skills: Array.isArray(values.skills)
          ? (values.skills as string[])
          : undefined,
      };

      const updated = await saveResumeProfile(
        profileId,
        updateData,
        controller.signal,
      );
      if (!mountedRef.current || controller.signal.aborted) return;
      setState({ phase: "ready", profile: updated, editing: false });
      message.success("编辑保存成功");
    } catch (error: unknown) {
      if (!mountedRef.current) return;
      if (controller?.signal.aborted) return;
      if (error instanceof Error) {
        message.error(error.message || "保存失败");
      }
      // 恢复到编辑模式，保留之前的 profile
      setState((prev) =>
        prev.phase === "saving"
          ? { phase: "ready", profile: prev.previousProfile, editing: true }
          : prev,
      );
    } finally {
      if (controller && requestControllerRef.current === controller) {
        requestControllerRef.current = null;
      }
    }
  };

  /** 确认画像 */
  const handleConfirm = async () => {
    if (!profileId) return;
    const previousProfile =
      state.phase === "ready"
        ? state.profile
        : state.phase === "confirmed"
          ? state.profile
          : undefined;
    if (!previousProfile) return;

    let controller: AbortController | null = null;
    try {
      setState({ phase: "saving", previousProfile });
      controller = beginRequest();
      const confirmed = await submitResumeConfirmation(
        profileId,
        controller.signal,
      );
      if (!mountedRef.current || controller.signal.aborted) return;
      setState({
        phase: "confirmed",
        profile: confirmed,
        vectorStatus: null,
        vectorizing: true,
      });
      message.success("简历画像已确认，正在准备简历检索索引。");
      void monitorVectorization(confirmed.id);
    } catch (error: unknown) {
      if (!mountedRef.current) return;
      if (controller?.signal.aborted) return;
      message.error(
        error instanceof Error ? error.message : "确认失败，请重试",
      );
      void loadProfile();
    } finally {
      if (controller && requestControllerRef.current === controller) {
        requestControllerRef.current = null;
      }
    }
  };

  /** 向量入库失败或超时后的手工重试。 */
  const handleVectorizationRetry = async () => {
    if (state.phase !== "confirmed" || !profileId) return;

    let controller: AbortController | null = null;
    try {
      setState((previous) =>
        previous.phase === "confirmed"
          ? { ...previous, vectorizing: true, vectorError: undefined }
          : previous,
      );
      controller = beginRequest();
      const status = await retryResumeVectorization(
        profileId,
        controller.signal,
      );
      if (!mountedRef.current || controller.signal.aborted) return;
      setState((previous) =>
        previous.phase === "confirmed"
          ? {
              ...previous,
              vectorStatus: status,
              vectorizing: true,
              vectorError: undefined,
            }
          : previous,
      );
      message.success("已重新提交向量入库任务。");
      void monitorVectorization(profileId);
    } catch (error: unknown) {
      if (!mountedRef.current || controller?.signal.aborted) return;
      message.error(
        error instanceof Error ? error.message : "重试提交失败，请稍后再试",
      );
      setState((previous) =>
        previous.phase === "confirmed"
          ? { ...previous, vectorizing: false }
          : previous,
      );
    } finally {
      if (controller && requestControllerRef.current === controller) {
        requestControllerRef.current = null;
      }
    }
  };

  /** 取消编辑 */
  const handleCancelEdit = () => {
    if (state.phase !== "ready") return;
    setState({ phase: "ready", profile: state.profile, editing: false });
  };

  // ==================== 渲染 ====================

  /* 加载中 / 保存中 */
  if (state.phase === "loading" || state.phase === "saving") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>
            简历确认
          </Typography.Title>
        </section>
        <Card className="resume-detail-card" bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <Spin
                indicator={<LoadingOutlined className="state-panel-icon" spin />}
              />
              <Typography.Title className="state-panel-title" level={3}>
                {state.phase === "saving" ? "正在保存画像" : "正在加载画像"}
              </Typography.Title>
              <Typography.Paragraph className="state-panel-description">
                {state.phase === "saving"
                  ? "请稍候，页面会在保存完成后自动更新。"
                  : "正在读取 AI 解析结果，请稍候。"}
              </Typography.Paragraph>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  /* 加载失败 */
  if (state.phase === "error") {
    return (
      <div className="page-stack">
        <section className="page-header">
          <Typography.Title className="page-title" level={1}>
            简历确认
          </Typography.Title>
        </section>
        <Card className="resume-detail-card" bordered={false}>
          <div className="state-panel">
            <div className="state-panel-content">
              <InfoCircleOutlined className="state-panel-icon" />
              <Typography.Title className="state-panel-title" level={3}>
                暂时无法加载画像
              </Typography.Title>
              <Typography.Paragraph className="state-panel-description">
                {state.message}
              </Typography.Paragraph>
              <Button onClick={() => navigate("/resume")} type="primary">
                返回上传页
              </Button>
            </div>
          </div>
        </Card>
      </div>
    );
  }

  // 此时已通过上方守卫子句排除了 loading/saving/error 阶段，
  // 当前只能为 ready 或 confirmed，两者均包含 profile 字段
  const profile: ResumeProfile = state.profile;
  const editing: boolean = state.phase === "ready" ? state.editing : false;
  const isConfirmed =
    state.phase === "confirmed" || profile.confirmStatus === "CONFIRMED";
  const vectorStatus =
    state.phase === "confirmed" ? state.vectorStatus : undefined;
  const vectorizationReady =
    isConfirmed && vectorStatus?.status === "SUCCESS";
  const vectorizationFailed =
    isConfirmed &&
    state.phase === "confirmed" &&
    !state.vectorizing &&
    Boolean(state.vectorError || vectorStatus?.status === "FAILED");

  // 安全读取 contactInfo
  const ci = profile.contactInfo as Record<string, string> | undefined;

  return (
    <div className="page-stack">
      <section className="page-header">
        <Space align="center" size={14}>
          <Typography.Title className="page-title" level={1}>
            简历确认
          </Typography.Title>
          {isConfirmed ? (
            <Tag color="success">已确认</Tag>
          ) : (
            <Tag color="warning">待确认</Tag>
          )}
        </Space>
        <Typography.Paragraph className="page-subtitle">
          {isConfirmed
            ? "画像已确认，可用于后续面试出题。如需修改请联系管理员。"
            : "请核对 AI 解析结果，可编辑关键字段后确认。"}
        </Typography.Paragraph>
      </section>

      <div className="resume-confirm-grid">
        <Card className="resume-confirm-panel" bordered={false}>
          <div className="panel-kicker">步骤二 · 确认画像</div>
          <div className="resume-source-preview">
            <div className="resume-source-icon">
              <FileTextOutlined aria-hidden="true" />
            </div>
            <p className="resume-source-title">AI 解析已完成</p>
            <p className="resume-source-text">
              请在右侧核对结构化信息。确认后，系统才会使用这份画像生成面试问题。
            </p>
            <div className="preview-grid">
              <div>
                <span className="preview-field-label">教育经历</span>
                <strong className="preview-field-value">
                  {profile.education?.length ?? 0} 段
                </strong>
              </div>
              <div>
                <span className="preview-field-label">工作经历</span>
                <strong className="preview-field-value">
                  {profile.workExperience?.length ?? 0} 段
                </strong>
              </div>
              <div>
                <span className="preview-field-label">项目经历</span>
                <strong className="preview-field-value">
                  {profile.projectExperience?.length ?? 0} 段
                </strong>
              </div>
              <div>
                <span className="preview-field-label">技能标签</span>
                <strong className="preview-field-value">
                  {profile.skills?.length ?? 0} 项
                </strong>
              </div>
            </div>
          </div>
        </Card>

        <Card className="resume-confirm-panel" bordered={false}>
          <div className="profile-card-header">
            <div>
              <Typography.Title className="profile-card-title" level={2}>
                结构化解析结果
              </Typography.Title>
              <Tag className="profile-card-status" color={isConfirmed ? "success" : "warning"}>
                {isConfirmed ? "画像已确认" : "待确认"}
              </Tag>
            </div>
            {!isConfirmed && !editing ? (
              <Button icon={<EditOutlined />} onClick={handleEdit} type="link">
                编辑
              </Button>
            ) : undefined}
          </div>

          {editing ? (
            <Form className="profile-edit-form" form={form} layout="vertical">
              <Form.Item
                label="姓名"
                name="candidateName"
                rules={[{ required: true, message: "请输入姓名" }]}
              >
                <Input placeholder="请输入候选人姓名" prefix={<UserOutlined />} />
              </Form.Item>
              <Form.Item label="手机号" name="phone">
                <Input placeholder="请输入手机号" prefix={<PhoneOutlined />} />
              </Form.Item>
              <Form.Item label="邮箱" name="email">
                <Input placeholder="请输入邮箱" prefix={<MailOutlined />} />
              </Form.Item>
              <Form.Item label="微信" name="wechat">
                <Input placeholder="请输入微信号" />
              </Form.Item>
              <Form.Item label="技能标签" name="skills">
                <Select
                  mode="tags"
                  placeholder="输入技能名称后按回车添加"
                  style={{ width: "100%" }}
                  tokenSeparators={[","]}
                />
              </Form.Item>
              <Space>
                <Button
                  icon={<SaveOutlined />}
                  onClick={() => void handleSave()}
                  type="primary"
                >
                  保存
                </Button>
                <Button onClick={handleCancelEdit}>取消</Button>
              </Space>
            </Form>
          ) : (
            <>
              <div className="profile-summary-grid">
                <div>
                  <span className="profile-field-label">姓名</span>
                  <strong className="profile-field-value">
                    {profile.candidateName || "未知"}
                  </strong>
                </div>
                <div>
                  <span className="profile-field-label">邮箱</span>
                  <strong className="profile-field-value">
                    {ci?.email || "-"}
                  </strong>
                </div>
                <div>
                  <span className="profile-field-label">手机号</span>
                  <strong className="profile-field-value">
                    {ci?.phone || "-"}
                  </strong>
                </div>
                <div>
                  <span className="profile-field-label">微信</span>
                  <strong className="profile-field-value">
                    {ci?.wechat || "-"}
                  </strong>
                </div>
              </div>
              <div className="profile-skill-block">
                <span className="profile-skill-title">核心技能（Skills）</span>
                <div className="profile-skill-list">
                  {profile.skills && profile.skills.length > 0 ? (
                    profile.skills.map((skill: string, index: number) => (
                      <Tag
                        className={index < 2 ? "is-highlighted" : undefined}
                        key={skill}
                      >
                        {skill}
                      </Tag>
                    ))
                  ) : (
                    <Typography.Text type="secondary">
                      暂无技能标签
                    </Typography.Text>
                  )}
                </div>
              </div>
            </>
          )}

          <div className="profile-action-bar">
            <div className="profile-action-hint">
              <InfoCircleOutlined aria-hidden="true" />
              <span>
                {isConfirmed
                  ? vectorizationReady
                    ? "简历检索索引已准备完成，可以选择面试方向。"
                    : vectorizationFailed
                      ? state.phase === "confirmed" && state.vectorError
                        ? state.vectorError
                        : "简历检索索引建立失败，请重试。"
                      : "画像已确认，正在建立简历检索索引，请稍候。"
                  : "确认画像后将进入模拟面试环节。"}
              </span>
            </div>
            <Space className="profile-actions" size={12} wrap>
              {isConfirmed ? (
                <>
                  {vectorizationReady ? (
                    <Button
                      icon={<CheckOutlined />}
                      onClick={() =>
                        navigate(`/interview?profileId=${profile.id}`)
                      }
                      type="primary"
                    >
                      选择面试方向
                    </Button>
                  ) : vectorizationFailed ? (
                    <Button
                      icon={<ReloadOutlined />}
                      loading={state.phase === "confirmed" && state.vectorizing}
                      onClick={() => void handleVectorizationRetry()}
                      type="primary"
                    >
                      重试向量入库
                    </Button>
                  ) : (
                    <Button
                      disabled
                      icon={<LoadingOutlined spin />}
                      loading={state.phase === "confirmed" && state.vectorizing}
                    >
                      正在准备检索索引
                    </Button>
                  )}
                  <Button onClick={() => navigate("/resume")}>
                    重新上传简历
                  </Button>
                </>
              ) : editing ? (
                <>
                  <Button
                    icon={<SaveOutlined />}
                    onClick={() => void handleSave()}
                    type="primary"
                  >
                    保存编辑
                  </Button>
                  <Button onClick={handleCancelEdit}>取消</Button>
                </>
              ) : (
                <>
                  <Button
                    icon={<CheckOutlined />}
                    onClick={() => void handleConfirm()}
                    type="primary"
                  >
                    确认画像
                  </Button>
                  <Button icon={<EditOutlined />} onClick={handleEdit}>
                    编辑画像
                  </Button>
                  <Button onClick={() => navigate("/resume")}>
                    返回上传页
                  </Button>
                </>
              )}
            </Space>
          </div>
        </Card>
      </div>

      <div className="resume-detail-stack">
        {/* 教育经历（只读） */}
        <Card className="resume-detail-card" bordered={false}>
          <Typography.Title className="detail-section-title" level={3}>
            教育经历
          </Typography.Title>
          {profile.education && profile.education.length > 0 ? (
            profile.education.map(
              (edu: Record<string, unknown>, index: number) => (
                <div key={index}>
                  {index > 0 && <Divider />}
                  <Descriptions column={2} size="small">
                    <Descriptions.Item label="学校">
                      {(edu.school as string) || "-"}
                    </Descriptions.Item>
                    <Descriptions.Item label="专业">
                      {(edu.major as string) || "-"}
                    </Descriptions.Item>
                    <Descriptions.Item label="学历">
                      {(edu.degree as string) || "-"}
                    </Descriptions.Item>
                    <Descriptions.Item label="时间">
                      {edu.startDate && edu.endDate
                        ? `${edu.startDate as string} ~ ${edu.endDate as string}`
                        : "-"}
                    </Descriptions.Item>
                    {edu.gpa ? (
                      <Descriptions.Item label="GPA">
                        {String(edu.gpa)}
                      </Descriptions.Item>
                    ) : null}
                  </Descriptions>
                </div>
              ),
            )
          ) : (
            <Empty
              description="暂无教育经历"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          )}
        </Card>

        {/* 工作经历（只读） */}
        <Card className="resume-detail-card" bordered={false}>
          <Typography.Title className="detail-section-title" level={3}>
            工作经历
          </Typography.Title>
          {profile.workExperience && profile.workExperience.length > 0 ? (
            profile.workExperience.map(
              (work: Record<string, unknown>, index: number) => (
                <div key={index}>
                  {index > 0 && <Divider />}
                  <Descriptions column={1} size="small">
                    <Descriptions.Item label="公司">
                      {(work.company as string) || "-"}
                    </Descriptions.Item>
                    <Descriptions.Item label="职位">
                      {(work.position as string) || "-"}
                    </Descriptions.Item>
                    <Descriptions.Item label="时间">
                      {work.startDate && work.endDate
                        ? `${work.startDate as string} ~ ${work.endDate as string}`
                        : "-"}
                    </Descriptions.Item>
                    {Boolean(work.responsibilities) ? (
                      <Descriptions.Item label="职责">
                        {Array.isArray(work.responsibilities)
                          ? work.responsibilities.join("；")
                          : String(work.responsibilities)}
                      </Descriptions.Item>
                    ) : null}
                    {Boolean(work.achievements) ? (
                      <Descriptions.Item label="成果">
                        {Array.isArray(work.achievements)
                          ? work.achievements.join("；")
                          : String(work.achievements)}
                      </Descriptions.Item>
                    ) : null}
                  </Descriptions>
                </div>
              ),
            )
          ) : (
            <Empty
              description="暂无工作经历"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          )}
        </Card>

        {/* 项目经历（只读） */}
        <Card className="resume-detail-card" bordered={false}>
          <Typography.Title className="detail-section-title" level={3}>
            项目经历
          </Typography.Title>
          {profile.projectExperience && profile.projectExperience.length > 0 ? (
            profile.projectExperience.map(
              (proj: Record<string, unknown>, index: number) => (
                <div key={index}>
                  {index > 0 && <Divider />}
                  <Descriptions column={1} size="small">
                    <Descriptions.Item label="项目名称">
                      {(proj.projectName as string) || "-"}
                    </Descriptions.Item>
                    <Descriptions.Item label="角色">
                      {(proj.role as string) || "-"}
                    </Descriptions.Item>
                    <Descriptions.Item label="时间">
                      {proj.startDate && proj.endDate
                        ? `${proj.startDate as string} ~ ${proj.endDate as string}`
                        : "-"}
                    </Descriptions.Item>
                    {Boolean(proj.description) ? (
                      <Descriptions.Item label="描述">
                        {String(proj.description)}
                      </Descriptions.Item>
                    ) : null}
                    {Boolean(proj.techStack) ? (
                      <Descriptions.Item label="技术栈">
                        {Array.isArray(proj.techStack)
                          ? (proj.techStack as string[]).map((t: string) => (
                              <Tag color="geekblue" key={t}>
                                {t}
                              </Tag>
                            ))
                          : String(proj.techStack)}
                      </Descriptions.Item>
                    ) : null}
                  </Descriptions>
                </div>
              ),
            )
          ) : (
            <Empty
              description="暂无项目经历"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          )}
        </Card>

        {/* 简历原文（可折叠） */}
        {profile.rawText && (
          <Card className="resume-detail-card" bordered={false}>
            <div className="profile-card-header">
              <Typography.Title className="detail-section-title" level={3}>
                简历原文
              </Typography.Title>
              <Typography.Text type="secondary">
                AI 解析原始输入
              </Typography.Text>
            </div>
            <Typography.Paragraph
              ellipsis={{ expandable: true, rows: 5, symbol: "展开全文" }}
              style={{ whiteSpace: "pre-wrap" }}
            >
              {profile.rawText}
            </Typography.Paragraph>
          </Card>
        )}

        <Card className="resume-detail-card" bordered={false}>
          <Typography.Text type="secondary">
            版本 {profile.version || 1}
            {profile.confirmedAt &&
              ` · 确认于 ${new Date(profile.confirmedAt).toLocaleString("zh-CN")}`}
            {profile.createdAt &&
              ` · 创建于 ${new Date(profile.createdAt).toLocaleString("zh-CN")}`}
          </Typography.Text>
        </Card>
      </div>
    </div>
  );
}
