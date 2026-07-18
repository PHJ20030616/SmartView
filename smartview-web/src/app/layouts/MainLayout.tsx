import {
  FileTextOutlined,
  HomeOutlined,
  MessageOutlined,
  PieChartOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Button, Layout, Menu, Space, Typography } from "antd";
import type { MenuProps } from "antd";
import { Outlet, useLocation, useNavigate } from "react-router-dom";

const { Header, Content, Sider } = Layout;

const menuItems: MenuProps["items"] = [
  { key: "/", icon: <HomeOutlined />, label: "首页" },
  { key: "/resume", icon: <FileTextOutlined />, label: "简历" },
  { key: "/interview", icon: <MessageOutlined />, label: "面试" },
  { key: "/report", icon: <PieChartOutlined />, label: "报告" },
];

export default function MainLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const selectedKey = location.pathname === "/" ? "/" : `/${location.pathname.split("/")[1]}`;

  return (
    <Layout className="app-shell">
      <Sider breakpoint="lg" collapsedWidth={0} className="app-sider" width={224}>
        <div className="app-brand">SmartView</div>
        <Menu
          className="app-menu"
          items={menuItems}
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header className="app-header">
          <Typography.Text strong>模拟面试工作台</Typography.Text>
          <Space>
            <Button icon={<UserOutlined />} onClick={() => navigate("/login")}>
              登录
            </Button>
          </Space>
        </Header>
        <Content className="app-content">
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
