/**
 * 应用根组件
 *
 * 配置应用级别的提供者：
 * - ConfigProvider: 配置 Ant Design 主题和国际化
 * - AntApp: 提供全局消息、通知等静态方法
 * - RouterProvider: 配置应用路由
 */
import { App as AntApp, ConfigProvider } from "antd";
import zhCN from "antd/locale/zh_CN";
import { RouterProvider } from "react-router-dom";

import { router } from "./router";

export default function App() {
  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          borderRadius: 6,
          colorPrimary: "#0f766e",
          colorText: "#172033",
          colorTextSecondary: "#667085",
          controlHeight: 42,
          fontFamily:
            '"Inter", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
          wireframe: false,
        },
        components: {
          Button: {
            controlHeight: 44,
            fontWeight: 600,
            primaryShadow: "none",
          },
          Form: {
            itemMarginBottom: 15,
            labelColor: "#344054",
            labelFontSize: 13,
          },
          Input: {
            activeBorderColor: "#0f766e",
            activeShadow: "0 0 0 3px rgba(15, 118, 110, 0.10)",
            hoverBorderColor: "#2b8c83",
          },
        },
      }}
    >
      <AntApp>
        <RouterProvider router={router} />
      </AntApp>
    </ConfigProvider>
  );
}
