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
