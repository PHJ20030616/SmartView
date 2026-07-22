import {
  ArrowRightOutlined,
  LockOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Alert, App, Button, Checkbox, Form, Input } from "antd";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { useState } from "react";

import {
  getSafeRedirectPath,
  useAuth,
} from "../../features/auth";
import type { LoginFormValues } from "../../features/auth/authTypes";
import {
  AUTH_PASSWORD_MAX_BYTES,
  getUtf8ByteLength,
  trimTextValue,
} from "../../features/auth/authValidation";
import AuthPageLayout from "./AuthPageLayout";

type LoginLocationState = {
  from?: unknown;
  registeredUsername?: unknown;
};

export default function LoginPage() {
  const { message } = App.useApp();
  const { login } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);
  const state = location.state as LoginLocationState | null;
  const searchParams = new URLSearchParams(location.search);
  const registeredUsername =
    typeof state?.registeredUsername === "string"
      ? state.registeredUsername
      : "";
  const redirectPath = getSafeRedirectPath(
    state?.from ?? searchParams.get("redirect"),
  );
  const sessionExpired = searchParams.get("reason") === "expired";

  /**
   * 页面只面向认证上下文提交凭据，Task 2.4 替换服务实现后无需改动表单流程。
   * 回跳地址在进入 navigate 前统一经过站内路径校验，避免开放重定向。
   */
  const handleFinish = async (values: LoginFormValues) => {
    setSubmitting(true);
    try {
      const result = await login(
        {
          username: values.username.trim(),
          password: values.password,
        },
        values.remember,
      );

      if (!result.persisted && values.remember) {
        message.warning("当前为临时登录，刷新后需要重新登录");
      } else {
        message.success("登录成功");
      }
      navigate(redirectPath, { replace: true });
    } catch {
      message.error("登录失败，请检查账号和密码后重试");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthPageLayout
      title="欢迎回来"
      description="登录后继续你的训练与成长记录"
      notice={
        sessionExpired ? (
          <Alert
            className="auth-session-alert"
            showIcon
            title="登录状态已失效，请重新登录"
            type="warning"
          />
        ) : undefined
      }
    >
      <Form<LoginFormValues>
        initialValues={{
          remember: true,
          username: registeredUsername,
        }}
        layout="vertical"
        onFinish={handleFinish}
        requiredMark={false}
        scrollToFirstError
      >
        <Form.Item
          label="用户名"
          name="username"
          rules={[
            {
              validator: (_, value) => {
                const username = trimTextValue(value);
                if (!username) {
                  return Promise.reject(new Error("请输入用户名"));
                }
                if (username.length < 3) {
                  return Promise.reject(
                    new Error("用户名至少需要 3 个字符"),
                  );
                }
                if (username.length > 32) {
                  return Promise.reject(
                    new Error("用户名不能超过 32 个字符"),
                  );
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <Input
            autoComplete="username"
            maxLength={32}
            placeholder="请输入用户名"
            prefix={<UserOutlined aria-hidden="true" />}
          />
        </Form.Item>

        <Form.Item
          label="密码"
          name="password"
          rules={[
            { required: true, message: "请输入密码" },
            { min: 6, message: "密码至少需要 6 个字符" },
            {
              validator: (_, value: string | undefined) => {
                if (
                  !value ||
                  getUtf8ByteLength(value) <= AUTH_PASSWORD_MAX_BYTES
                ) {
                  return Promise.resolve();
                }
                return Promise.reject(
                  new Error("密码的 UTF-8 编码不能超过 72 字节"),
                );
              },
            },
          ]}
        >
          <Input.Password
            autoComplete="current-password"
            placeholder="请输入密码"
            prefix={<LockOutlined aria-hidden="true" />}
          />
        </Form.Item>

        <Form.Item name="remember" valuePropName="checked">
          <Checkbox>保持登录状态</Checkbox>
        </Form.Item>

        <Button
          aria-label="登录"
          block
          disabled={submitting}
          htmlType="submit"
          icon={<ArrowRightOutlined aria-hidden="true" />}
          iconPlacement="end"
          loading={submitting}
          type="primary"
        >
          登录
        </Button>
      </Form>

      <div className="auth-mode-switch">
        <span>还没有账号？</span>
        <Link to="/register">创建账户</Link>
      </div>
    </AuthPageLayout>
  );
}
