/**
 * 面试会话占位页
 *
 * 真实面试会话（阶段计划生成、逐题追问、报告沉淀）属于 v0.5 任务；
 * 当前仅承载画像分析成功后的"开始面试"入口跳转，提示功能开发中。
 */
import { ApiOutlined } from "@ant-design/icons";
import { Button, Card, Typography } from "antd";
import { useNavigate, useSearchParams } from "react-router-dom";

export default function InterviewSessionPlaceholder() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const profileId = searchParams.get("profileId") ?? "";
  const roleDirection = searchParams.get("roleDirection") ?? "";

  // 回到方向选择页时必须带回 profileId，否则面试页会进入"缺少简历画像"状态。
  const handleBackToDirection = () => {
    navigate(
      profileId
        ? `/interview?profileId=${encodeURIComponent(profileId)}`
        : "/interview",
    );
  };

  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>
          模拟面试
        </Typography.Title>
        <Typography.Paragraph className="page-subtitle">
          {roleDirection === "JAVA_BACKEND"
            ? "Java 后端方向"
            : roleDirection === "AGENT_DEVELOPMENT"
              ? "Agent 开发方向"
              : "面试会话"}
        </Typography.Paragraph>
      </section>
      <Card bordered={false}>
        <div className="state-panel">
          <div className="state-panel-content">
            <ApiOutlined className="state-panel-icon" />
            <Typography.Title className="state-panel-title" level={3}>
              面试会话功能开发中
            </Typography.Title>
            <Typography.Paragraph className="state-panel-description">
              画像分析已就绪，真实面试会话将在下一阶段（v0.5）开放。
            </Typography.Paragraph>
            <Button onClick={handleBackToDirection} type="primary">
              返回方向选择
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
