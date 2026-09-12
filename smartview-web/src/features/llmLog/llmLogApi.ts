import { request } from "../../api/request";
import type { components } from "../../api/generated/schema";

/** 后端统一响应包装（与 interviewApi / reportApi 保持一致） */
interface ApiResponseWrapper<T> {
  code: string;
  message: string;
  data: T | null;
  traceId: string;
  timestamp: string;
}

type LlmCallPage = components["schemas"]["LlmCallPage"];

/** 查询参数：过滤项留空表示不过滤 */
export interface LlmCallQuery {
  scene?: string;
  promptVersion?: string;
  from?: string;
  to?: string;
  page: number;
  size: number;
}

/** 安全提取响应数据，data 为 null 时抛明确错误，避免静默崩溃 */
function extractData<T>(wrapper: ApiResponseWrapper<T>, endpoint: string): T {
  if (wrapper.data == null) {
    throw new Error(`接口 ${endpoint} 返回数据为空`);
  }
  return wrapper.data;
}

/**
 * 查询 LLM 调用记录 GET /api/llm-calls。
 *
 * 该接口是跨用户的运维视图，非白名单账号会收到 403，
 * 错误文案由 llmLogService.toLlmLogError 统一提取。
 */
export async function listLlmCallsApi(
  query: LlmCallQuery,
  signal?: AbortSignal,
): Promise<LlmCallPage> {
  const response = await request.get<ApiResponseWrapper<LlmCallPage>>("/llm-calls", {
    params: {
      // 空字符串会被后端当作"有值"参与过滤，因此统一转成 undefined 让它不出现在查询串里
      scene: query.scene || undefined,
      promptVersion: query.promptVersion || undefined,
      from: query.from || undefined,
      to: query.to || undefined,
      page: query.page,
      size: query.size,
    },
    signal,
  });
  return extractData(response.data, "/llm-calls");
}
