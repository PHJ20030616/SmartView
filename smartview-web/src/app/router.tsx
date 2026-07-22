/**
 * 应用路由配置
 *
 * 定义应用的路由结构：
 * - /login、/register: 仅未登录用户访问的认证页面
 * - /: 主应用区域（使用 MainLayout 布局）
 *   - 首页、简历管理、面试准备、面试报告等子页面
 * - *: 未匹配路由重定向到首页
 */
import {
  createBrowserRouter,
  Navigate,
  Outlet,
  type RouteObject,
} from "react-router-dom";

import MainLayout from "./layouts/MainLayout";
import {
  AnonymousOnlyRoute,
  AuthProvider,
  ProtectedRoute,
} from "../features/auth";
import HomePage from "../pages/home/HomePage";
import InterviewPage from "../pages/interview/InterviewPage";
import LoginPage from "../pages/login/LoginPage";
import RegisterPage from "../pages/login/RegisterPage";
import ReportPage from "../pages/report/ReportPage";
import ResumePage from "../pages/resume/ResumePage";

export const appRoutes: RouteObject[] = [
  {
    element: (
      <AuthProvider>
        <Outlet />
      </AuthProvider>
    ),
    children: [
      {
        element: <AnonymousOnlyRoute />,
        children: [
          { path: "/login", element: <LoginPage /> },
          { path: "/register", element: <RegisterPage /> },
        ],
      },
      {
        element: <ProtectedRoute />,
        children: [
          {
            path: "/",
            element: <MainLayout />,
            children: [
              { index: true, element: <HomePage /> },
              { path: "resume", element: <ResumePage /> },
              { path: "interview", element: <InterviewPage /> },
              { path: "report", element: <ReportPage /> },
            ],
          },
        ],
      },
      {
        path: "*",
        element: <Navigate to="/" replace />,
      },
    ],
  },
];

export const router = createBrowserRouter(appRoutes);
