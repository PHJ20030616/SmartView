/**
 * 报告页面组件
 *
 * 提供面试复盘报告功能：
 * - 展示面试准备度评估
 * - 分析岗位匹配度
 * - 列出风险点和改进建议
 * - 支持报告导出
 */
import {
  BarChartOutlined,
  DownloadOutlined,
  FileSearchOutlined,
  LockOutlined,
} from "@ant-design/icons";
import { Button, Card, Empty, Typography } from "antd";

export default function ReportPage() {
  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>
          复盘报告
        </Typography.Title>
        <Typography.Paragraph className="page-subtitle">
          查看历史模拟面试记录，点击查看详细分析报告。
        </Typography.Paragraph>
      </section>

      <Card className="report-empty-panel" bordered={false}>
        {/* 不伪造报告列表数据，真实报告接口接入后可直接替换此空状态内容。 */}
        <Empty
          description="完成一次模拟面试后，这里会展示准备度、风险点和学习建议。"
          image={<BarChartOutlined style={{ color: "#c7d1dc", fontSize: 64 }} />}
        >
          <Button disabled icon={<DownloadOutlined />} type="primary">
            导出报告
          </Button>
        </Empty>
        <div className="report-preview-hint">
          <FileSearchOutlined aria-hidden="true" />
          <span>报告会基于真实面试记录生成，当前暂无可展示内容。</span>
          <LockOutlined aria-hidden="true" />
        </div>
      </Card>
    </div>
  );
}
