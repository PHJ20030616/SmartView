/**
 * 主布局组件
 *
 * 提供应用的整体布局结构：
 * - 左侧侧边栏：品牌标识和导航菜单
 * - 顶部导航栏：页面标题和用户操作
 * - 内容区域：子路由页面内容
 */
import {
  FileTextOutlined,
  HomeOutlined,
  LogoutOutlined,
  PieChartOutlined,
  SafetyCertificateFilled,
} from "@ant-design/icons";
import { Avatar, Button, Layout, Menu, Space, Tooltip, Typography } from "antd";
import type { MenuProps } from "antd";
import { Outlet, useLocation, useNavigate } from "react-router-dom";

import { useAuth } from "../../features/auth";

const { Header, Content, Sider } = Layout;

// 面试页保留独立路由，但当前阶段从主导航中隐藏，避免用户进入尚未接入真实请求的占位工作台。
const menuItems: MenuProps["items"] = [
  { key: "/", icon: <HomeOutlined />, label: "首页" },
  { key: "/resume", icon: <FileTextOutlined />, label: "简历" },
  { key: "/report", icon: <PieChartOutlined />, label: "报告" },
];

export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { logout, user } = useAuth();
  // 提取当前一级路由作为选中的菜单项
  const selectedKey = location.pathname === "/" ? "/" : `/${location.pathname.split("/")[1]}`;

  const handleLogout = () => {
    // 先清理内存与本地持久化会话，再替换历史记录，避免退出后通过返回键回到受保护页面。
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <Layout className="app-shell">
      {/* 左侧侧边栏 */}
      <Sider breakpoint="lg" collapsedWidth={0} className="app-sider" width={252}>
        <div className="app-brand">
          <SafetyCertificateFilled aria-hidden="true" />
          <span>SmartView</span>
        </div>
        <Menu
          className="app-menu"
          items={menuItems}
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        {/* 顶部导航栏 */}
        <Header className="app-header">
          <Typography.Text className="app-header-title" strong>
            模拟面试工作台
          </Typography.Text>
          <Space className="header-user" size={12}>
            <Typography.Text className="header-user-name">
              {user?.nickname ?? user?.username}
            </Typography.Text>
            <Avatar className="header-avatar">
              {(user?.nickname ?? user?.username ?? "U").slice(0, 1).toUpperCase()}
            </Avatar>
            <Tooltip title="退出登录">
              <Button
                aria-label="退出登录"
                icon={<LogoutOutlined aria-hidden="true" />}
                onClick={handleLogout}
                type="text"
              />
            </Tooltip>
          </Space>
        </Header>
        {/* 内容区域 */}
        <Content className="app-content">
          <div className="app-content-inner">
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
}
