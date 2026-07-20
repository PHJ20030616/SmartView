/**
 * 简历页面组件
 *
 * 提供简历上传和解析功能：
 * - 支持 PDF 格式的简历上传
 * - 上传后生成结构化简历画像
 * - 画像作为后续面试出题的依据
 */
import { InboxOutlined } from "@ant-design/icons";
import { Button, Card, Space, Typography, Upload } from "antd";

export default function ResumePage() {
  return (
    <Space className="page-stack" direction="vertical" size={24}>
      <section>
        <Typography.Title level={2}>简历</Typography.Title>
        <Typography.Paragraph type="secondary">上传 PDF 简历后，系统会生成可确认的结构化画像。</Typography.Paragraph>
      </section>
      <Card>
        <Upload.Dragger accept="application/pdf" beforeUpload={() => false} maxCount={1}>
          <p className="ant-upload-drag-icon">
            <InboxOutlined />
          </p>
          <p className="ant-upload-text">拖拽或点击上传 PDF 简历</p>
          <p className="ant-upload-hint">当前页面提供基础入口，真实解析流程将在后续任务接入。</p>
        </Upload.Dragger>
        <div className="page-actions">
          <Button type="primary">提交解析</Button>
        </div>
      </Card>
    </Space>
  );
}
