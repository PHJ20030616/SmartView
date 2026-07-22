import {
  BarChartOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
} from "@ant-design/icons";
import { Typography } from "antd";
import type { PropsWithChildren, ReactNode } from "react";

import "./auth-pages.css";

type AuthPageLayoutProps = PropsWithChildren<{
  title: string;
  description: string;
  notice?: ReactNode;
}>;

const brandFeatures = [
  {
    icon: <TeamOutlined aria-hidden="true" />,
    title: "建立个人面试档案",
    description: "沉淀每一次练习与反馈",
  },
  {
    icon: <BarChartOutlined aria-hidden="true" />,
    title: "持续追踪能力变化",
    description: "看见表达与专业能力的成长",
  },
  {
    icon: <SafetyCertificateOutlined aria-hidden="true" />,
    title: "获得针对性练习建议",
    description: "让下一次准备更有方向",
  },
];

export default function AuthPageLayout({
  children,
  description,
  notice,
  title,
}: AuthPageLayoutProps) {
  return (
    <main className="auth-shell">
      <section className="auth-frame" aria-label="SmartView 账户入口">
        <aside className="auth-brand-panel">
          <div className="auth-brand-mark">
            <span className="auth-brand-logo" aria-hidden="true">
              SV
            </span>
            <span>SmartView</span>
          </div>

          <div className="auth-brand-copy">
            <Typography.Title level={1}>
              从一次有目标的
              <br />
              练习开始
            </Typography.Title>
            <Typography.Paragraph>
              把每次准备变成可追踪、可复盘、可持续提升的成长记录。
            </Typography.Paragraph>
          </div>

          <div className="auth-feature-list">
            {brandFeatures.map((feature) => (
              <div className="auth-feature" key={feature.title}>
                <span className="auth-feature-icon">{feature.icon}</span>
                <span>
                  <strong>{feature.title}</strong>
                  <small>{feature.description}</small>
                </span>
              </div>
            ))}
          </div>

          <div className="auth-brand-footer">智能面试训练与能力评估平台</div>
        </aside>

        <section className="auth-form-panel">
          <div className="auth-mobile-brand">
            <span className="auth-brand-logo" aria-hidden="true">
              SV
            </span>
            <span>SmartView</span>
          </div>

          <div className="auth-form-container">
            <div className="auth-form-heading">
              <Typography.Title level={2}>{title}</Typography.Title>
              <Typography.Paragraph>{description}</Typography.Paragraph>
            </div>
            {notice}
            {children}
          </div>
        </section>
      </section>
    </main>
  );
}
