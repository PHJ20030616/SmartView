import { FileSearchOutlined, MessageOutlined, PieChartOutlined } from "@ant-design/icons";
import { Button, Card, Col, Row, Space, Typography } from "antd";
import { useNavigate } from "react-router-dom";

const actions = [
  {
    title: "简历画像",
    description: "上传并确认结构化简历画像，作为后续出题依据。",
    path: "/resume",
    icon: <FileSearchOutlined />,
  },
  {
    title: "模拟面试",
    description: "围绕岗位方向进行动态问答，持续追踪覆盖情况。",
    path: "/interview",
    icon: <MessageOutlined />,
  },
  {
    title: "复盘报告",
    description: "查看准备度、风险点、学习建议和参考答案。",
    path: "/report",
    icon: <PieChartOutlined />,
  },
];

export default function HomePage() {
  const navigate = useNavigate();

  return (
    <Space className="page-stack" direction="vertical" size={24}>
      <section>
        <Typography.Title level={2}>首页</Typography.Title>
        <Typography.Paragraph type="secondary">从简历画像开始，完成一次可复盘的模拟面试。</Typography.Paragraph>
      </section>
      <Row gutter={[16, 16]}>
        {actions.map((action) => (
          <Col xs={24} md={8} key={action.path}>
            <Card className="workflow-card">
              <Space direction="vertical" size={12}>
                <Typography.Title level={4}>{action.title}</Typography.Title>
                <Typography.Paragraph type="secondary">{action.description}</Typography.Paragraph>
                <Button icon={action.icon} onClick={() => navigate(action.path)} type="primary">
                  进入
                </Button>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>
    </Space>
  );
}
