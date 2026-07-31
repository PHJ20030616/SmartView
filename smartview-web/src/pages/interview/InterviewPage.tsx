/**
 * 面试页面组件
 *
 * 提供模拟面试功能：
 * - 基于简历画像动态生成面试问题
 * - 支持回答输入和提交
 * - 记录面试对话历史用于后续复盘
 */
import {
  ClockCircleFilled,
  MessageOutlined,
  RobotOutlined,
  SendOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Button, Card, Input, Typography } from "antd";

export default function InterviewPage() {
  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>
          模拟面试
        </Typography.Title>
        <Typography.Paragraph className="page-subtitle">
          围绕候选人画像进行动态追问，并沉淀可复盘的回答记录。
        </Typography.Paragraph>
      </section>

      {/* 当前页面暂未接入面试会话请求，展示工作台结构但不伪造会话数据。 */}
      <div className="interview-toolbar">
        <div className="interview-toolbar-item">
          <RobotOutlined aria-hidden="true" />
          <span>
            主题：<strong>等待简历画像</strong>
          </span>
        </div>
        <div className="interview-toolbar-item">
          <MessageOutlined aria-hidden="true" />
          <span>
            问题：<strong>0 / 10</strong>
          </span>
        </div>
        <div className="interview-toolbar-item">
          <ClockCircleFilled aria-hidden="true" />
          <span className="interview-toolbar-status">00:00</span>
        </div>
        <Button danger disabled>
          结束面试
        </Button>
      </div>

      <Card className="interview-conversation" bordered={false}>
        <div className="interview-message">
          <div className="interview-message-avatar">
            <RobotOutlined aria-hidden="true" />
          </div>
          <div className="interview-message-bubble">
            请先完成简历画像确认，系统将在这里生成第一道面试问题。
          </div>
        </div>
        <div className="interview-message user">
          <div className="interview-message-avatar">
            <UserOutlined aria-hidden="true" />
          </div>
          <div className="interview-message-bubble">
            完成画像确认后，你的回答记录会显示在这里。
          </div>
        </div>
      </Card>

      <Card className="interview-composer" bordered={false}>
        <Input.TextArea
          autoSize={{ minRows: 2, maxRows: 5 }}
          aria-describedby="interview-composer-status"
          disabled
          placeholder="在此输入你的回答..."
        />
        <Button disabled icon={<SendOutlined />} type="primary">
          发送
        </Button>
        <Typography.Text
          className="interview-composer-status"
          id="interview-composer-status"
          type="secondary"
        >
          当前版本暂未开启真实面试问答，回答输入暂不可用。
        </Typography.Text>
      </Card>
    </div>
  );
}
