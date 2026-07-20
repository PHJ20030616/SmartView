/**
 * SmartView 前端应用入口文件
 *
 * 负责初始化 React 应用并挂载到 DOM 根节点。
 */
import React from "react";
import ReactDOM from "react-dom/client";

import App from "./app/App";
import "./styles/global.css";

// 将应用挂载到 root 节点，使用严格模式检测潜在问题
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
