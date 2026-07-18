import { createBrowserRouter, Navigate } from "react-router-dom";

import MainLayout from "./layouts/MainLayout";
import HomePage from "../pages/home/HomePage";
import InterviewPage from "../pages/interview/InterviewPage";
import LoginPage from "../pages/login/LoginPage";
import ReportPage from "../pages/report/ReportPage";
import ResumePage from "../pages/resume/ResumePage";

export const router = createBrowserRouter([
  {
    path: "/login",
    element: <LoginPage />,
  },
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
  {
    path: "*",
    element: <Navigate to="/" replace />,
  },
]);
