import { DownloadOutlined } from "@ant-design/icons";
import { Button, Card, Empty, Space, Typography } from "antd";

export default function ReportPage() {
  return (
    <Space className="page-stack" direction="vertical" size={24}>
      <section>
        <Typography.Title level={2}>报告</Typography.Title>
        <Typography.Paragraph type="secondary">面试结束后查看准备度、岗位匹配、风险点和学习建议。</Typography.Paragraph>
      </section>
      <Card>
        <Empty description="暂无报告">
          <Button icon={<DownloadOutlined />} type="primary">
            导出报告
          </Button>
        </Empty>
      </Card>
    </Space>
  );
}
