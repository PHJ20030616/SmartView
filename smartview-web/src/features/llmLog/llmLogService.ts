import type { components } from "../../api/generated/schema";
import { listLlmCallsApi, type LlmCallQuery } from "./llmLogApi";

type LlmCallPage = components["schemas"]["LlmCallPage"];

/** 携带 HTTP 状态码的展示层错误，便于页面区分 403 与其他失败 */
export class LlmLogError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = "LlmLogError";
    this.status = status;
  }
}

/**
 * 把任意异常转换成可展示的中文错误。
 *
 * 优先使用后端统一响应里的中文 message；拿不到时（网络中断等）才回退到调用方给的兜底文案，
 * 避免把 axios 的英文默认提示暴露给用户。这里用结构化判断而不是 instanceof，
 * 与 reportService 的既有写法一致，同时便于测试传入普通对象。
 */
export function toLlmLogError(error: unknown, fallback: string): LlmLogError {
  if (error instanceof LlmLogError) {
    return error;
  }
  const typed = (error ?? {}) as {
    message?: string;
    response?: { status?: number; data?: { message?: string } };
  };
  const status = typed.response?.status;
  const backendMessage = typed.response?.data?.message;
  return new LlmLogError(backendMessage || fallback, status);
}

/** 查询调用记录；失败时抛出统一的 LlmLogError */
export async function fetchLlmCalls(
  query: LlmCallQuery,
  signal?: AbortSignal,
): Promise<LlmCallPage> {
  try {
    return await listLlmCallsApi(query, signal);
  } catch (error) {
    throw toLlmLogError(error, "调用记录加载失败，请重试");
  }
}
