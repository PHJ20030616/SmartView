import { SendOutlined } from "@ant-design/icons";
import { Button, Card, Input, Space, Typography } from "antd";

export default function InterviewPage() {
  return (
    <Space className="page-stack" direction="vertical" size={24}>
      <section>
        <Typography.Title level={2}>面试</Typography.Title>
        <Typography.Paragraph type="secondary">围绕候选人画像进行动态追问，并沉淀可复盘的回答记录。</Typography.Paragraph>
      </section>
      <Card>
        <Space className="interview-panel" direction="vertical" size={16}>
          <Typography.Text strong>当前问题</Typography.Text>
          <Typography.Paragraph>请先完成简历画像确认，系统将在这里生成第一道面试问题。</Typography.Paragraph>
          <Input.TextArea rows={6} placeholder="请输入你的回答" />
          <Button icon={<SendOutlined />} type="primary">
            提交回答
          </Button>
        </Space>
      </Card>
    </Space>
  );
}
