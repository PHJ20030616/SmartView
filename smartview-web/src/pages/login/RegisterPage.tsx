import {
  ArrowRightOutlined,
  LockOutlined,
  MailOutlined,
  PhoneOutlined,
  TeamOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { App, Button, Form, Input } from "antd";
import { Link, useNavigate } from "react-router-dom";
import { useState } from "react";

import * as authService from "../../features/auth/authService";
import type { RegisterFormValues } from "../../features/auth/authTypes";
import {
  AUTH_PASSWORD_MAX_BYTES,
  getUtf8ByteLength,
  trimTextValue,
} from "../../features/auth/authValidation";
import AuthPageLayout from "./AuthPageLayout";

export default function RegisterPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [submitting, setSubmitting] = useState(false);

  const handleFinish = async (values: RegisterFormValues) => {
    setSubmitting(true);
    try {
      const { confirmPassword: _confirmPassword, ...request } = values;

      // 可选字段在空白时不发送，保持 Mock 与后续真实接口的请求语义一致。
      await authService.register({
        ...request,
        username: request.username.trim(),
        nickname: request.nickname.trim(),
        email: request.email?.trim() || undefined,
        phone: request.phone?.trim() || undefined,
      });
      message.success("注册成功，请登录");
      navigate("/login", {
        replace: true,
        state: { registeredUsername: values.username.trim() },
      });
    } catch {
      message.error("注册失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthPageLayout
      title="创建账户"
      description="填写基本信息，开始你的面试准备"
    >
      <Form<RegisterFormValues>
        className="auth-register-form"
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
            placeholder="请输入 3-32 个字符"
            prefix={<UserOutlined aria-hidden="true" />}
          />
        </Form.Item>

        <Form.Item
          label="昵称"
          name="nickname"
          rules={[
            {
              validator: (_, value) => {
                const nickname = trimTextValue(value);
                if (!nickname) {
                  return Promise.reject(new Error("请输入昵称"));
                }
                if (nickname.length > 64) {
                  return Promise.reject(
                    new Error("昵称不能超过 64 个字符"),
                  );
                }
                return Promise.resolve();
              },
            },
          ]}
        >
          <Input
            maxLength={64}
            placeholder="请输入希望展示的昵称"
            prefix={<TeamOutlined aria-hidden="true" />}
          />
        </Form.Item>

        <Form.Item
          label="邮箱（选填）"
          name="email"
          rules={[
            {
              transform: trimTextValue,
              type: "email",
              message: "请输入正确的邮箱地址",
            },
          ]}
        >
          <Input
            autoComplete="email"
            placeholder="例如：name@example.com"
            prefix={<MailOutlined aria-hidden="true" />}
          />
        </Form.Item>

        <Form.Item
          label="手机号（选填）"
          name="phone"
          rules={[
            {
              transform: trimTextValue,
              pattern: /^1[3-9]\d{9}$/,
              message: "请输入正确的 11 位手机号",
            },
          ]}
        >
          <Input
            autoComplete="tel"
            maxLength={11}
            placeholder="请输入手机号"
            prefix={<PhoneOutlined aria-hidden="true" />}
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
            autoComplete="new-password"
            placeholder="请输入至少 6 个字符，最多 72 字节"
            prefix={<LockOutlined aria-hidden="true" />}
          />
        </Form.Item>

        <Form.Item
          dependencies={["password"]}
          label="确认密码"
          name="confirmPassword"
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
            placeholder="请再次输入密码"
            prefix={<LockOutlined aria-hidden="true" />}
          />
        </Form.Item>

        <Button
          aria-label="注册账户"
          block
          disabled={submitting}
          htmlType="submit"
          icon={<ArrowRightOutlined aria-hidden="true" />}
          iconPlacement="end"
          loading={submitting}
          type="primary"
        >
          注册账户
        </Button>
      </Form>

      <div className="auth-mode-switch">
        <span>已有账号？</span>
        <Link to="/login">返回登录</Link>
      </div>
    </AuthPageLayout>
  );
}
