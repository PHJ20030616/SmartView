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
import InterviewHistoryPage from "../pages/interview/InterviewHistoryPage";
import InterviewPage from "../pages/interview/InterviewPage";
import InterviewSessionPage from "../pages/interview/InterviewSessionPage";
import LoginPage from "../pages/login/LoginPage";
import RegisterPage from "../pages/login/RegisterPage";
import LlmCallLogPage from "../pages/observability/LlmCallLogPage";
import ReportPage from "../pages/report/ReportPage";
import ResumeConfirmPage from "../pages/resume/ResumeConfirmPage";
import ResumeHistoryPage from "../pages/resume/ResumeHistoryPage";
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
              { path: "resume/history", element: <ResumeHistoryPage /> },
              { path: "resume/confirm/:profileId", element: <ResumeConfirmPage /> },
              { path: "interview", element: <InterviewPage /> },
              { path: "interview/history", element: <InterviewHistoryPage /> },
              {
                // 面试会话页：Task 5.5 由占位页切换为真实会话页面
                path: "interview/session",
                element: <InterviewSessionPage />,
              },
              { path: "report", element: <ReportPage /> },
              {
                // LLM 调用观测看板：只读运维视图，访问权限由后端白名单控制；
                // 使用一级路径 /observability，使 MainLayout 的 selectedKey 能直接命中菜单 key
                path: "observability",
                element: <LlmCallLogPage />,
              },
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
