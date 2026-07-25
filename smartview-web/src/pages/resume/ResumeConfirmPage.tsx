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
  LoadingOutlined,
  MailOutlined,
  PhoneOutlined,
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
  UpdateResumeProfileRequest,
} from "../../features/resume";
import {
  fetchResumeProfile,
  saveResumeProfile,
  submitResumeConfirmation,
} from "../../features/resume";

/** 页面加载/操作状态 */
type PageState =
  | { phase: "loading" }
  | { phase: "error"; message: string }
  | { phase: "ready"; profile: ResumeProfile; editing: boolean }
  | { phase: "saving"; previousProfile: ResumeProfile }
  | { phase: "confirmed"; profile: ResumeProfile };

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

  useEffect(() => {
    return () => {
      mountedRef.current = false;
      requestControllerRef.current?.abort();
    };
  }, []);

  /** 开始新的画像请求，并取消同一页面上仍未完成的旧请求。 */
  const beginRequest = () => {
    requestControllerRef.current?.abort();
    const controller = new AbortController();
    requestControllerRef.current = controller;
    return controller;
  };

  /** 加载画像数据 */
  const loadProfile = useCallback(async () => {
    if (!profileId) {
      if (!mountedRef.current) return;
      setState({ phase: "error", message: "缺少画像 ID 参数" });
      return;
    }
    const requestId = ++loadRequestIdRef.current;
    setState({ phase: "loading" });
    const controller = beginRequest();
    try {
      const profile = await fetchResumeProfile(profileId, controller.signal);
      if (!mountedRef.current || requestId !== loadRequestIdRef.current) return;
      setState({ phase: "ready", profile, editing: false });
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
  }, [profileId]);

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
      setState({ phase: "confirmed", profile: confirmed });
      message.success("简历画像已确认，可以开始面试了！");
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

  /** 取消编辑 */
  const handleCancelEdit = () => {
    if (state.phase !== "ready") return;
    setState({ phase: "ready", profile: state.profile, editing: false });
  };

  // ==================== 渲染 ====================

  /* 加载中 / 保存中 */
  if (state.phase === "loading" || state.phase === "saving") {
    return (
      <Space className="page-stack" orientation="vertical" size={24}>
        <section>
          <Typography.Title level={2}>简历确认</Typography.Title>
        </section>
        <Card>
          <div style={{ padding: 48, textAlign: "center" }}>
            <Spin
              indicator={<LoadingOutlined style={{ fontSize: 32 }} spin />}
            />
            <Typography.Paragraph
              style={{ marginTop: 16 }}
              type="secondary"
            >
              {state.phase === "saving" ? "保存中..." : "正在加载简历画像..."}
            </Typography.Paragraph>
          </div>
        </Card>
      </Space>
    );
  }

  /* 加载失败 */
  if (state.phase === "error") {
    return (
      <Space className="page-stack" orientation="vertical" size={24}>
        <section>
          <Typography.Title level={2}>简历确认</Typography.Title>
        </section>
        <Card>
          <Empty description={state.message}>
            <Button onClick={() => navigate("/resume")} type="primary">
              返回上传页
            </Button>
          </Empty>
        </Card>
      </Space>
    );
  }

  // 此时已通过上方守卫子句排除了 loading/saving/error 阶段，
  // 当前只能为 ready 或 confirmed，两者均包含 profile 字段
  const profile: ResumeProfile = state.profile;
  const editing: boolean = state.phase === "ready" ? state.editing : false;
  const isConfirmed =
    state.phase === "confirmed" || profile.confirmStatus === "CONFIRMED";

  // 安全读取 contactInfo
  const ci = profile.contactInfo as Record<string, string> | undefined;

  return (
    <Space className="page-stack" orientation="vertical" size={24}>
      {/* 页头 */}
      <section>
        <Space align="center" size={16}>
          <Typography.Title level={2} style={{ margin: 0 }}>
            简历确认
          </Typography.Title>
          {isConfirmed ? (
            <Tag color="green">已确认</Tag>
          ) : (
            <Tag color="orange">待确认</Tag>
          )}
        </Space>
        <Typography.Paragraph type="secondary">
          {isConfirmed
            ? "画像已确认，可用于后续面试出题。如需修改请联系管理员。"
            : "请核对 AI 解析结果，可编辑关键字段后确认。"}
        </Typography.Paragraph>
      </section>

      {/* 基本信息 */}
      <Card
        extra={
          !isConfirmed && !editing ? (
            <Button icon={<EditOutlined />} onClick={handleEdit} type="link">
              编辑
            </Button>
          ) : undefined
        }
        title="基本信息"
      >
        {editing ? (
          <Form form={form} layout="vertical">
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
          <Descriptions column={1} size="small">
            <Descriptions.Item label="姓名">
              {profile.candidateName || "未知"}
            </Descriptions.Item>
            <Descriptions.Item label="手机号">
              {ci?.phone || "-"}
            </Descriptions.Item>
            <Descriptions.Item label="邮箱">
              {ci?.email || "-"}
            </Descriptions.Item>
            <Descriptions.Item label="微信">
              {ci?.wechat || "-"}
            </Descriptions.Item>
            <Descriptions.Item label="技能">
              {profile.skills && profile.skills.length > 0
                ? profile.skills.map((skill: string) => (
                    <Tag color="blue" key={skill}>
                      {skill}
                    </Tag>
                  ))
                : "-"}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Card>

      {/* 教育经历（只读） */}
      <Card title="教育经历">
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
      <Card title="工作经历">
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
      <Card title="项目经历">
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
        <Card
          extra={
            <Typography.Text type="secondary">
              AI 解析原始输入
            </Typography.Text>
          }
          title="简历原文"
        >
          <Typography.Paragraph
            ellipsis={{ expandable: true, rows: 5, symbol: "展开全文" }}
            style={{ whiteSpace: "pre-wrap" }}
          >
            {profile.rawText}
          </Typography.Paragraph>
        </Card>
      )}

      {/* 底部操作区 */}
      <Card>
        <Space size={16}>
          {isConfirmed ? (
            <>
              <Button
                icon={<CheckOutlined />}
                onClick={() => navigate("/interview")}
                type="primary"
              >
                开始模拟面试
              </Button>
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

        {/* 元数据信息 */}
        <div style={{ marginTop: 16 }}>
          <Typography.Text type="secondary">
            版本 {profile.version || 1}
            {profile.confirmedAt &&
              ` · 确认于 ${new Date(profile.confirmedAt).toLocaleString("zh-CN")}`}
            {profile.createdAt &&
              ` · 创建于 ${new Date(profile.createdAt).toLocaleString("zh-CN")}`}
          </Typography.Text>
        </div>
      </Card>
    </Space>
  );
}
