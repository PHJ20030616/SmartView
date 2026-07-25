/**
 * 简历上传页面组件
 *
 * 提供简历上传和解析功能：
 * - 支持 PDF 格式的简历上传
 * - 上传后自动轮询解析状态
 * - 解析完成后跳转到确认页面
 * - 解析失败时展示错误信息
 * - 组件卸载时自动取消异步操作，避免内存泄漏
 */
import { InboxOutlined } from "@ant-design/icons";
import {
  Alert,
  App,
  Button,
  Card,
  Progress,
  Space,
  Typography,
  Upload,
} from "antd";
import type { RcFile, UploadFile } from "antd/es/upload/interface";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import {
  isResumeParseAbortError,
  uploadAndWaitForParse,
} from "../../features/resume";

/** 上传流程状态 */
type UploadState =
  | { phase: "idle" }
  | { phase: "uploading"; fileName: string }
  | { phase: "parsing"; fileName: string }
  | { phase: "failed"; error: string };

export default function ResumePage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [fileList, setFileList] = useState<UploadFile[]>([]);
  const [state, setState] = useState<UploadState>({ phase: "idle" });
  // 用于组件卸载后取消异步回调，避免在已卸载组件上 setState/navigate
  const mountedRef = useRef(true);
  // 同时取消 Axios 请求和轮询等待，避免离开页面后继续占用网络与定时器。
  const abortControllerRef = useRef<AbortController | null>(null);

  /** 在进入上传流程前拦截格式和大小错误，避免用户等待后才收到后端拒绝。 */
  const handleBeforeUpload = (file: RcFile) => {
    const isPdf =
      file.type.toLowerCase() === "application/pdf" ||
      /\.pdf$/i.test(file.name);
    if (!isPdf) {
      message.error("仅支持上传 PDF 文件");
      return Upload.LIST_IGNORE;
    }
    if (file.size > 10 * 1024 * 1024) {
      message.error("简历文件大小不能超过 10MB");
      return Upload.LIST_IGNORE;
    }
    // 保留手动提交行为，文件先进入列表，点击“提交解析”后才真正上传。
    return false;
  };

  useEffect(() => {
    return () => {
      mountedRef.current = false;
      abortControllerRef.current?.abort();
    };
  }, []);

  /**
   * 处理提交解析
   * 上传文件 → 切换到解析阶段 → 轮询等待 → 跳转确认页
   */
  const handleSubmit = async () => {
    const file = fileList[0]?.originFileObj;
    if (!file) {
      message.warning("请先选择 PDF 文件");
      return;
    }

    setState({ phase: "uploading", fileName: file.name });
    const abortController = new AbortController();
    abortControllerRef.current?.abort();
    abortControllerRef.current = abortController;

    try {
      const result = await uploadAndWaitForParse(file, (parsePhase) => {
        // 上传完成后回调：从上传阶段切换到解析阶段
        if (parsePhase === "parse" && mountedRef.current) {
          setState({ phase: "parsing", fileName: file.name });
        }
      }, abortController.signal);

      // 组件已卸载（用户离开页面），不再执行后续操作
      if (!mountedRef.current) return;

      setState({ phase: "idle" });

      if (result.parseStatus === "SUCCESS" && result.profileId) {
        message.success("解析完成，即将跳转到确认页面");
        navigate(`/resume/confirm/${result.profileId}`, { replace: true });
      } else {
        setState({
          phase: "failed",
          error: "解析完成但未获取到画像信息，请重试",
        });
      }
    } catch (error) {
      if (!mountedRef.current || isResumeParseAbortError(error)) return;
      const errorMsg =
        error instanceof Error ? error.message : "上传或解析失败，请重试";
      setState({
        phase: "failed",
        error: errorMsg,
      });
      message.error(errorMsg);
    } finally {
      if (abortControllerRef.current === abortController) {
        abortControllerRef.current = null;
      }
    }
  };

  /**
   * 重置状态，允许重新上传
   */
  const handleRetry = () => {
    setState({ phase: "idle" });
    setFileList([]);
  };

  const isProcessing =
    state.phase === "uploading" || state.phase === "parsing";

  return (
    <Space className="page-stack" orientation="vertical" size={24}>
      <section>
        <Typography.Title level={2}>简历</Typography.Title>
        <Typography.Paragraph type="secondary">
          上传 PDF 简历后，系统将自动解析并生成结构化画像，解析完成后可进入确认页面校验和编辑。
        </Typography.Paragraph>
      </section>

      <Card>
        {state.phase === "failed" ? (
          /* 解析失败时展示错误信息和重试入口 */
          <Space direction="vertical" size={16} style={{ width: "100%" }}>
            <Alert
              closable
              description={state.error}
              message="解析失败"
              showIcon
              type="error"
            />
            <div className="page-actions">
              <Button onClick={handleRetry} type="primary">
                重新上传
              </Button>
            </div>
          </Space>
        ) : (
          <>
            <Upload.Dragger
              accept="application/pdf"
              beforeUpload={handleBeforeUpload}
              disabled={isProcessing}
              fileList={fileList}
              maxCount={1}
              onChange={({ fileList: newFileList }) =>
                setFileList(newFileList)
              }
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">拖拽或点击上传 PDF 简历</p>
              <p className="ant-upload-hint">
                仅支持 PDF 格式，文件大小不超过 10MB
              </p>
            </Upload.Dragger>

            {/* 上传进度提示 */}
            {state.phase === "uploading" && (
              <div style={{ marginTop: 16 }}>
                <Progress
                  percent={99}
                  status="active"
                  strokeColor={{ from: "#108ee9", to: "#87d068" }}
                />
                <Typography.Text
                  type="secondary"
                  style={{ display: "block", marginTop: 8, textAlign: "center" }}
                >
                  正在上传 {state.fileName}...
                </Typography.Text>
              </div>
            )}

            {/* 解析进度提示 */}
            {state.phase === "parsing" && (
              <div style={{ marginTop: 16 }}>
                <Progress
                  percent={70}
                  status="active"
                  strokeColor={{ from: "#108ee9", to: "#87d068" }}
                />
                <Typography.Text
                  type="secondary"
                  style={{ display: "block", marginTop: 8, textAlign: "center" }}
                >
                  正在解析 {state.fileName}，请稍候...
                </Typography.Text>
              </div>
            )}

            <div className="page-actions">
              <Button
                disabled={fileList.length === 0 || isProcessing}
                loading={isProcessing}
                onClick={handleSubmit}
                type="primary"
              >
                {state.phase === "uploading"
                  ? "上传中..."
                  : state.phase === "parsing"
                    ? "解析中..."
                    : "提交解析"}
              </Button>
            </div>
          </>
        )}
      </Card>
    </Space>
  );
}
