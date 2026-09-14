import axios from "axios";

import { createTraceId } from "../../api/http";
import type { components } from "../../api/generated/schema";
import {
  createInterviewSessionApi,
  finishInterviewSessionApi,
  getInterviewSessionApi,
  submitAnswerApi,
} from "./interviewApi";

type InterviewSession = components["schemas"]["InterviewSession"];
type SubmitAnswerData = components["schemas"]["SubmitAnswerData"];
type RoleDirection = components["schemas"]["CreateInterviewSessionRequest"]["roleDirection"];
type InterviewQuestion = components["schemas"]["InterviewQuestion"];

/** 会话操作错误：携带 HTTP 状态码，页面据此做 409 对账等处理 */
export class InterviewError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "InterviewError";
    this.status = status;
  }
}

/** 把未知错误收敛为 InterviewError，优先取后端 message 便于页面直接展示 */
export function toInterviewError(error: unknown, fallback: string): InterviewError {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status;
    const message = (error.response?.data as { message?: string } | undefined)?.message;
    // 网络层超时：axios 原文是英文 "timeout of 65000ms exceeded"，直接展示既不友好，
    // 又会鼓励用户立刻重按——而此刻服务端很可能仍在评估（提交接口 65s 超时，
    // 略大于服务端 60s 读超时，保证先拿到服务端的中文错误）。
    // 这里统一换成中文提示，明确"可能仍在处理，先别重复提交"。
    // 注意排除主动取消：取消是组件卸载等正常流程，不该提示超时。
    // 浏览器 XHR 路径下超时的 code 是 ECONNABORTED；主动取消走 axios 的 ERR_CANCELED，
    // 用 axios.isCancel 判定而不是比对 message 文案（后者是 axios 内部实现细节）。
    if (!error.response && error.code === "ECONNABORTED" && !axios.isCancel(error)) {
      return new InterviewError(
        "等待响应超时：服务端可能仍在处理，请稍后刷新查看结果，不要重复提交",
      );
    }
    return new InterviewError(message || error.message || fallback, status);
  }
  return new InterviewError(error instanceof Error ? error.message : fallback);
}

/** 是否 409 冲突（题目已过期/会话已推进），页面据此对账刷新 */
export function isConflictError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 409;
}

/** 生成幂等请求 ID（UUID v4，复用 HTTP 层 UUID 生成器的降级逻辑） */
function createRequestId(): string {
  return createTraceId();
}

/** 创建面试会话（含首题与进度范围） */
export async function createSession(
  resumeProfileId: string,
  roleDirection: RoleDirection,
  signal?: AbortSignal,
): Promise<InterviewSession> {
  return createInterviewSessionApi(resumeProfileId, roleDirection, signal);
}

/** 恢复会话详情（页面刷新后展示当前题与历史问答） */
export async function restoreSession(
  sessionId: string,
  signal?: AbortSignal,
): Promise<InterviewSession> {
  return getInterviewSessionApi(sessionId, signal);
}

/** 提交回答：生成幂等 requestId，返回评估与下一题 */
export async function submitAnswer(
  sessionId: string,
  question: InterviewQuestion,
  answerText: string,
  durationSeconds: number,
  signal?: AbortSignal,
): Promise<SubmitAnswerData> {
  const requestId = createRequestId();
  return submitAnswerApi(sessionId, question.id, answerText, requestId, durationSeconds, signal);
}

/** 提前结束面试，返回结束后的会话 */
export async function finishSession(
  sessionId: string,
  signal?: AbortSignal,
): Promise<InterviewSession> {
  return finishInterviewSessionApi(sessionId, signal);
}
