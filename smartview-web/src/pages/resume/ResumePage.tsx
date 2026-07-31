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
import {
  CheckCircleFilled,
  FilePdfOutlined,
  InboxOutlined,
  InfoCircleOutlined,
} from "@ant-design/icons";
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
    // 挂载时将 mountedRef 恢复为 true：StrictMode 开发模式会额外执行一次
    // setup -> cleanup -> setup，若 setup 不重置，cleanup 会把 mountedRef 置为
    // false，导致上传/解析流程中的卸载守卫全部失效（页面一直停在“上传中”）。
    mountedRef.current = true;
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
  const selectedFile = fileList[0]?.originFileObj;

  const fileSizeLabel = selectedFile
    ? `${(selectedFile.size / 1024 / 1024).toFixed(1)} MB`
    : "-";

  return (
    <div className="page-stack">
      <section className="page-header">
        <Typography.Title className="page-title" level={1}>
          简历画像
        </Typography.Title>
        <Typography.Paragraph className="page-subtitle">
          上传 PDF 简历后，系统将自动解析并生成结构化画像，解析完成后可进入确认页面校验和编辑。
        </Typography.Paragraph>
      </section>

      <div className="resume-workspace">
        <Card className="resume-upload-card" bordered={false}>
          <div className="panel-kicker">步骤一 · 上传文件</div>
          <Typography.Title className="panel-title" level={2}>
            上传你的简历
          </Typography.Title>
          <Typography.Paragraph className="panel-description">
            系统会提取教育、工作、项目和技能信息，生成可用于面试出题的结构化画像。
          </Typography.Paragraph>

          {state.phase === "failed" ? (
            /* 解析失败时仍保留原上传入口，用户可以在当前页直接重试。 */
            <Space direction="vertical" size={16} style={{ width: "100%" }}>
              <Alert
                closable
                description={state.error}
                message="解析失败"
                showIcon
                type="error"
              />
              <Button onClick={handleRetry} type="primary">
                重新上传
              </Button>
            </Space>
          ) : (
            <>
              <Upload.Dragger
                className="resume-dropzone"
                accept="application/pdf"
                beforeUpload={handleBeforeUpload}
                disabled={isProcessing}
                fileList={fileList}
                maxCount={1}
                showUploadList={false}
                onChange={({ fileList: newFileList }) =>
                  setFileList(newFileList)
                }
              >
                <div className="upload-illustration">
                  <InboxOutlined aria-hidden="true" />
                </div>
                <p className="upload-title">拖拽文件到此处</p>
                <p className="upload-hint">
                  支持 PDF 格式，文件大小不超过 10MB
                </p>
              </Upload.Dragger>

              {selectedFile && (
                <div className="selected-file">
                  <FilePdfOutlined
                    aria-hidden="true"
                    className="selected-file-icon"
                  />
                  <div className="selected-file-content">
                    <span className="selected-file-name">
                      {selectedFile.name}
                    </span>
                    <span className="selected-file-meta">
                      {fileSizeLabel} · PDF 文件
                    </span>
                  </div>
                  <CheckCircleFilled
                    aria-label="文件已选择"
                    className="selected-file-icon"
                  />
                </div>
              )}

              {/* 进度数字与请求服务层保持一致，仅将阶段状态换成更清晰的卡片提示。 */}
              {state.phase === "uploading" && (
                <div className="progress-block">
                  <Progress
                    percent={99}
                    status="active"
                    strokeColor="#0f9f94"
                  />
                  <Typography.Text type="secondary">
                    正在上传 {state.fileName}...
                  </Typography.Text>
                </div>
              )}

              {state.phase === "parsing" && (
                <div className="progress-block">
                  <Progress
                    percent={70}
                    status="active"
                    strokeColor="#0f9f94"
                  />
                  <Typography.Text type="secondary">
                    正在解析 {state.fileName}，请稍候...
                  </Typography.Text>
                </div>
              )}

              <div className="page-actions">
                <Button
                  disabled={fileList.length === 0 || isProcessing}
                  loading={isProcessing}
                  onClick={() => void handleSubmit()}
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

        <Card className="resume-preview-card" bordered={false}>
          <div className="preview-header">
            <Typography.Title className="panel-title" level={2}>
              结构化解析结果
            </Typography.Title>
            <Typography.Text className="preview-status">
              {state.phase === "parsing"
                ? "解析中"
                : state.phase === "uploading"
                  ? "上传中"
                  : selectedFile
                    ? "待提交"
                    : "等待上传"}
            </Typography.Text>
          </div>

          {selectedFile ? (
            <>
              <div className="preview-grid">
                <div>
                  <span className="preview-field-label">文件名称</span>
                  <strong className="preview-field-value">
                    {selectedFile.name}
                  </strong>
                </div>
                <div>
                  <span className="preview-field-label">文件类型</span>
                  <strong className="preview-field-value">PDF</strong>
                </div>
                <div>
                  <span className="preview-field-label">文件大小</span>
                  <strong className="preview-field-value">
                    {fileSizeLabel}
                  </strong>
                </div>
                <div>
                  <span className="preview-field-label">当前状态</span>
                  <strong className="preview-field-value">
                    {state.phase === "parsing"
                      ? "AI 解析中"
                      : state.phase === "uploading"
                        ? "上传中"
                        : "等待提交解析"}
                  </strong>
                </div>
              </div>
              <div className="preview-note">
                <InfoCircleOutlined aria-hidden="true" />
                <span>
                  提交后会自动轮询解析状态。解析成功后，页面将跳转到画像确认环节。
                </span>
              </div>
            </>
          ) : (
            <div className="preview-empty">
              <FilePdfOutlined aria-hidden="true" />
              <p className="preview-empty-title">等待简历文件</p>
              <p className="preview-empty-description">
                选择 PDF 文件后，这里会显示文件信息和解析状态。
              </p>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
