import React, { useState } from "react";
import { createRoot } from "react-dom/client";
import {
  App as AntdApp,
  Button,
  Checkbox,
  ConfigProvider,
  Form,
  Input,
  Typography,
} from "antd";
import {
  ArrowRightOutlined,
  BarChartOutlined,
  EyeInvisibleOutlined,
  EyeTwoTone,
  LockOutlined,
  MailOutlined,
  PhoneOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  UserOutlined,
} from "@ant-design/icons";
import zhCN from "antd/locale/zh_CN";

type AuthMode = "login" | "register";

interface LoginValues {
  username: string;
  password: string;
  remember: boolean;
}

interface RegisterValues {
  username: string;
  nickname: string;
  email?: string;
  phone?: string;
  password: string;
  confirmPassword: string;
}

const brandFeatures = [
  {
    icon: <TeamOutlined />,
    title: "建立个人面试档案",
    description: "沉淀每一次练习与反馈",
  },
  {
    icon: <BarChartOutlined />,
    title: "持续追踪能力变化",
    description: "看见表达与专业能力的成长",
  },
  {
    icon: <SafetyCertificateOutlined />,
    title: "获得针对性练习建议",
    description: "让下一次准备更有方向",
  },
];

function AuthPrototype() {
  const [mode, setMode] = useState<AuthMode>("register");
  const [submitting, setSubmitting] = useState(false);
  const { message } = AntdApp.useApp();
  const [loginForm] = Form.useForm<LoginValues>();
  const [registerForm] = Form.useForm<RegisterValues>();

  const switchMode = (nextMode: AuthMode) => {
    // 两套表单共享同一展示区域，切换时清理校验状态，避免错误提示串到另一页面。
    loginForm.resetFields();
    registerForm.resetFields();
    setMode(nextMode);
    setSubmitting(false);
  };

  const simulateSubmit = async (action: string) => {
    setSubmitting(true);

    // 当前阶段只验证页面体验，避免原型误调用尚未联调的后端接口。
    await new Promise((resolve) => window.setTimeout(resolve, 700));

    setSubmitting(false);
    message.success(`${action}表单校验通过，接口将在 Task 2.4 接入`);
  };

  const passwordIcon = (visible: boolean) =>
    visible ? <EyeTwoTone /> : <EyeInvisibleOutlined />;

  return (
    <main className="auth-shell">
      <section className="auth-frame" aria-label="SmartView 账号入口">
        <aside className="brand-panel">
          <div className="brand-mark">
            <span className="brand-logo" aria-hidden="true">
              SV
            </span>
            <span>SmartView</span>
          </div>

          <div className="brand-copy">
            <Typography.Title level={1}>
              从一次有目标的
              <br />
              练习开始
            </Typography.Title>
            <Typography.Paragraph>
              把每次准备变成可追踪、可复盘、可持续提升的成长记录。
            </Typography.Paragraph>
          </div>

          <div className="brand-feature-list">
            {brandFeatures.map((feature) => (
              <div className="brand-feature" key={feature.title}>
                <span className="feature-icon">{feature.icon}</span>
                <span>
                  <strong>{feature.title}</strong>
                  <small>{feature.description}</small>
                </span>
              </div>
            ))}
          </div>

          <div className="brand-footer">智能面试训练与能力评估平台</div>
        </aside>

        <section className="form-panel">
          <div className="mobile-brand">
            <span className="brand-logo" aria-hidden="true">
              SV
            </span>
            <span>SmartView</span>
          </div>

          <div className="form-container">
            <div className="form-heading">
              <Typography.Title level={2}>
                {mode === "register" ? "创建账号" : "欢迎回来"}
              </Typography.Title>
              <Typography.Paragraph>
                {mode === "register"
                  ? "填写基本信息，开始你的面试准备"
                  : "登录后继续你的训练与成长记录"}
              </Typography.Paragraph>
            </div>

            {mode === "register" ? (
              <Form<RegisterValues>
                key="register-form"
                form={registerForm}
                layout="vertical"
                requiredMark={false}
                scrollToFirstError
                onFinish={() => simulateSubmit("注册")}
              >
                <Form.Item
                  label="用户名"
                  name="username"
                  rules={[
                    { required: true, message: "请输入用户名" },
                    { min: 3, message: "用户名至少需要 3 个字符" },
                    { max: 32, message: "用户名不能超过 32 个字符" },
                  ]}
                >
                  <Input
                    autoComplete="username"
                    maxLength={32}
                    prefix={<UserOutlined />}
                    placeholder="请输入 3-32 个字符"
                  />
                </Form.Item>

                <Form.Item
                  label="昵称"
                  name="nickname"
                  rules={[
                    { required: true, message: "请输入昵称" },
                    { max: 64, message: "昵称不能超过 64 个字符" },
                  ]}
                >
                  <Input
                    maxLength={64}
                    prefix={<TeamOutlined />}
                    placeholder="请输入希望展示的昵称"
                  />
                </Form.Item>

                <Form.Item
                  label="邮箱（选填）"
                  name="email"
                  rules={[{ type: "email", message: "请输入正确的邮箱地址" }]}
                >
                  <Input
                    autoComplete="email"
                    prefix={<MailOutlined />}
                    placeholder="例如：name@example.com"
                  />
                </Form.Item>

                <Form.Item
                  label="手机号（选填）"
                  name="phone"
                  rules={[
                    {
                      pattern: /^1[3-9]\d{9}$/,
                      message: "请输入正确的 11 位手机号",
                    },
                  ]}
                >
                  <Input
                    autoComplete="tel"
                    maxLength={11}
                    prefix={<PhoneOutlined />}
                    placeholder="请输入手机号"
                  />
                </Form.Item>

                <Form.Item
                  label="密码"
                  name="password"
                  rules={[
                    { required: true, message: "请输入密码" },
                    { min: 6, message: "密码至少需要 6 个字符" },
                    { max: 128, message: "密码不能超过 128 个字符" },
                  ]}
                >
                  <Input.Password
                    autoComplete="new-password"
                    iconRender={passwordIcon}
                    prefix={<LockOutlined />}
                    placeholder="请输入 6-128 个字符"
                  />
                </Form.Item>

                <Form.Item
                  label="确认密码"
                  name="confirmPassword"
                  dependencies={["password"]}
                  rules={[
                    { required: true, message: "请再次输入密码" },
                    ({ getFieldValue }) => ({
                      validator(_, value) {
                        if (!value || getFieldValue("password") === value) {
                          return Promise.resolve();
                        }
                        return Promise.reject(new Error("两次输入的密码不一致"));
                      },
                    }),
                  ]}
                >
                  <Input.Password
                    autoComplete="new-password"
                    iconRender={passwordIcon}
                    prefix={<LockOutlined />}
                    placeholder="请再次输入密码"
                  />
                </Form.Item>

                <Button
                  block
                  htmlType="submit"
                  icon={<ArrowRightOutlined />}
                  iconPosition="end"
                  loading={submitting}
                  type="primary"
                >
                  注册账号
                </Button>
              </Form>
            ) : (
              <Form<LoginValues>
                key="login-form"
                form={loginForm}
                initialValues={{ remember: true }}
                layout="vertical"
                requiredMark={false}
                scrollToFirstError
                onFinish={() => simulateSubmit("登录")}
              >
                <Form.Item
                  label="用户名"
                  name="username"
                  rules={[{ required: true, message: "请输入用户名" }]}
                >
                  <Input
                    autoComplete="username"
                    prefix={<UserOutlined />}
                    placeholder="请输入用户名"
                  />
                </Form.Item>

                <Form.Item
                  label="密码"
                  name="password"
                  rules={[{ required: true, message: "请输入密码" }]}
                >
                  <Input.Password
                    autoComplete="current-password"
                    iconRender={passwordIcon}
                    prefix={<LockOutlined />}
                    placeholder="请输入密码"
                  />
                </Form.Item>

                <Form.Item name="remember" valuePropName="checked">
                  <Checkbox>保持登录状态</Checkbox>
                </Form.Item>

                <Button
                  block
                  htmlType="submit"
                  icon={<ArrowRightOutlined />}
                  iconPosition="end"
                  loading={submitting}
                  type="primary"
                >
                  登录
                </Button>
              </Form>
            )}

            <div className="mode-switch">
              <span>{mode === "register" ? "已有账号？" : "还没有账号？"}</span>
              <Button
                type="link"
                onClick={() =>
                  switchMode(mode === "register" ? "login" : "register")
                }
              >
                {mode === "register" ? "返回登录" : "创建账号"}
              </Button>
            </div>
          </div>
        </section>
      </section>
    </main>
  );
}

function PrototypeRoot() {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          borderRadius: 6,
          colorPrimary: "#0f766e",
          colorText: "#172033",
          colorTextSecondary: "#667085",
          controlHeight: 42,
          fontFamily:
            '"Inter", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
          wireframe: false,
        },
        components: {
          Button: {
            controlHeight: 44,
            fontWeight: 600,
            primaryShadow: "none",
          },
          Form: {
            itemMarginBottom: 15,
            labelColor: "#344054",
            labelFontSize: 13,
          },
          Input: {
            activeBorderColor: "#0f766e",
            activeShadow: "0 0 0 3px rgba(15, 118, 110, 0.10)",
            hoverBorderColor: "#2b8c83",
          },
        },
      }}
    >
      <AntdApp>
        <AuthPrototype />
      </AntdApp>
    </ConfigProvider>
  );
}

const rootElement = document.getElementById("auth-prototype-root");

if (rootElement) {
  createRoot(rootElement).render(<PrototypeRoot />);
}
