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
    <ConfigProvider locale={zhCN} theme={{ token: { borderRadius: 6 } }}>
      <AntApp>
        <RouterProvider router={router} />
      </AntApp>
    </ConfigProvider>
  );
}
