import { LoginOutlined } from "@ant-design/icons";
import { Button, Card, Form, Input, Typography } from "antd";
import { useNavigate } from "react-router-dom";

type LoginFormValues = {
  username: string;
  password: string;
};

export default function LoginPage() {
  const navigate = useNavigate();

  const handleFinish = (_values: LoginFormValues) => {
    navigate("/");
  };

  return (
    <main className="login-page">
      <Card className="login-panel">
        <Typography.Title level={2}>登录 SmartView</Typography.Title>
        <Typography.Paragraph type="secondary">进入模拟面试工作台，继续简历分析和面试练习。</Typography.Paragraph>
        <Form<LoginFormValues> layout="vertical" onFinish={handleFinish}>
          <Form.Item label="账号" name="username" rules={[{ required: true, message: "请输入账号" }]}>
            <Input placeholder="请输入账号" autoComplete="username" />
          </Form.Item>
          <Form.Item label="密码" name="password" rules={[{ required: true, message: "请输入密码" }]}>
            <Input.Password placeholder="请输入密码" autoComplete="current-password" />
          </Form.Item>
          <Button block htmlType="submit" icon={<LoginOutlined />} type="primary">
            登录
          </Button>
        </Form>
      </Card>
    </main>
  );
}
