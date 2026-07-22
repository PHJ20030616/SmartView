/**
 * 报告页面组件
 *
 * 提供面试复盘报告功能：
 * - 展示面试准备度评估
 * - 分析岗位匹配度
 * - 列出风险点和改进建议
 * - 支持报告导出
 */
import { DownloadOutlined } from "@ant-design/icons";
import { Button, Card, Empty, Space, Typography } from "antd";

export default function ReportPage() {
  return (
    <Space className="page-stack" orientation="vertical" size={24}>
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
