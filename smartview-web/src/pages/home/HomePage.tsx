/**
 * 首页组件
 *
 * 展示应用的核心功能入口卡片：
 * - 简历画像：上传和确认简历信息
 * - 模拟面试：进行动态面试问答
 * - 复盘报告：查看面试准备度和建议
 */
import {
  ArrowRightOutlined,
  CheckCircleFilled,
  FileSearchOutlined,
  LockOutlined,
  MessageOutlined,
  PieChartOutlined,
} from "@ant-design/icons";
import { Card, Typography } from "antd";
import { useNavigate } from "react-router-dom";

/**
 * 首页的四步流程只表达产品当前的操作顺序，不依赖后端状态接口。
 * 面试和报告在当前版本仍未接入真实请求，因此以锁定态展示，避免误导用户。
 */
const actions = [
  {
    title: "简历画像",
    description: "上传并确认结构化简历画像，作为后续出题依据。",
    path: "/resume",
    icon: <FileSearchOutlined />,
    locked: false,
  },
  {
    title: "模拟面试",
    description: "确认画像后，从简历确认页进入面试工作台。",
    path: "/interview",
    icon: <MessageOutlined />,
    locked: true,
  },
  {
    title: "复盘报告",
    description: "查看准备度、风险点、学习建议和参考答案。",
    path: "/report",
    icon: <PieChartOutlined />,
    locked: true,
  },
];

export default function HomePage() {
  const navigate = useNavigate();

  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>
          首页
        </Typography.Title>
        <Typography.Paragraph className="page-subtitle">
          从简历画像开始，完成一次可复盘的模拟面试。
        </Typography.Paragraph>
      </section>

      <nav aria-label="面试准备流程" className="workflow-progress">
        <div aria-current="step" className="workflow-progress-step active">
          <FileSearchOutlined aria-hidden="true" />
          <span>1. 上传简历</span>
        </div>
        <ArrowRightOutlined className="workflow-progress-arrow" aria-hidden="true" />
        <div className="workflow-progress-step">
          <CheckCircleFilled aria-hidden="true" />
          <span>2. 确认画像</span>
        </div>
        <ArrowRightOutlined className="workflow-progress-arrow" aria-hidden="true" />
        <div className="workflow-progress-step">
          <MessageOutlined aria-hidden="true" />
          <span>3. 模拟面试</span>
        </div>
        <ArrowRightOutlined className="workflow-progress-arrow" aria-hidden="true" />
        <div className="workflow-progress-step">
          <PieChartOutlined aria-hidden="true" />
          <span>4. 复盘报告</span>
        </div>
      </nav>

      <div className="workflow-grid">
        {actions.map((action) => (
          <Card
            className={`workflow-card${action.locked ? " is-locked" : ""}`}
            key={action.path}
            onClick={action.locked ? undefined : () => navigate(action.path)}
            role={action.locked ? undefined : "button"}
            aria-label={`${action.title}${action.locked ? "，当前锁定" : ""}。${action.description}`}
            tabIndex={action.locked ? undefined : 0}
            onKeyDown={
              action.locked
                ? undefined
                : (event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      navigate(action.path);
                    }
                  }
            }
          >
            <div className="workflow-card-icon">{action.icon}</div>
            <Typography.Title className="workflow-card-title" level={3}>
              {action.title}
            </Typography.Title>
            <Typography.Paragraph className="workflow-card-description">
              {action.description}
            </Typography.Paragraph>
            {action.locked ? (
              <div className="workflow-card-lock">
                <span>锁定</span>
                <LockOutlined aria-hidden="true" />
              </div>
            ) : (
              <span className="workflow-card-link">
                <span>进入</span>
                <ArrowRightOutlined aria-hidden="true" />
              </span>
            )}
          </Card>
        ))}
      </div>
    </div>
  );
}
