/**
 * 登录页面组件
 *
 * 提供用户登录表单，包含账号和密码输入。
 * 登录成功后跳转到首页。
 */
import { LoginOutlined } from "@ant-design/icons";
import { Button, Card, Form, Input, Typography } from "antd";
import { useNavigate } from "react-router-dom";

/**
 * 登录表单数据类型
 */
type LoginFormValues = {
  username: string;
  password: string;
};

export default function LoginPage() {
  const navigate = useNavigate();

  /**
   * 处理登录表单提交
   *
   * @param _values - 表单值（包含用户名和密码）
   */
  const handleFinish = (_values: LoginFormValues) => {
    // TODO: 调用登录 API
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
