import { request } from "../../api/request";
import type { components } from "../../api/generated/schema";

/** 后端统一响应包装 */
interface ApiResponseWrapper<T> {
  code: string;
  message: string;
  data: T | null;
  traceId: string;
  timestamp: string;
}

type InterviewSession = components["schemas"]["InterviewSession"];
type SubmitAnswerData = components["schemas"]["SubmitAnswerData"];
type RoleDirection = components["schemas"]["CreateInterviewSessionRequest"]["roleDirection"];
type InterviewSessionPage = components["schemas"]["InterviewSessionPage"];

/**
 * 提交回答的独立超时（65s）。
 *
 * 提交链路内含多次 LLM 调用（回答评估 + 追问候选生成，实测均值 17.7s、最坏 37.8s），
 * 沿用全局 15s 会让"服务端已落库并推进、前端却报超时"的假失败达到 61%，用户重按还会
 * 白烧一次 LLM 配额。这里单独放宽，其余接口继续用全局 15s，快速失败不受影响。
 * （更早的实测里 43.8s 是候选池接口的重建耗时；提交链路已不再同步重建候选池，
 * 因此当前口径按 37.8s 计。）
 *
 * 取值原则是"略大于服务端超时"：Spring 侧 `ai-service.read-timeout-ms` 与生产 Nginx
 * `proxy_read_timeout` 都是 60s，客户端等 65s 就能拿到服务端返回的中文错误提示
 * （而不是浏览器先断开、只剩英文的 timeout 文案）。
 */
export const SUBMIT_ANSWER_TIMEOUT_MS = 65000;

/**
 * 安全提取响应数据，data 为 null 时抛明确错误。
 * 避免使用非空断言 `!`，防止后端返回 data:null 时前端静默崩溃。
 */
function extractData<T>(wrapper: ApiResponseWrapper<T>, endpoint: string): T {
  if (wrapper.data == null) {
    throw new Error(`接口 ${endpoint} 返回数据为空`);
  }
  return wrapper.data;
}

/** 创建面试会话（含首题与进度范围） POST /api/interview-sessions */
export async function createInterviewSessionApi(
  resumeProfileId: string,
  roleDirection: RoleDirection,
  signal?: AbortSignal,
): Promise<InterviewSession> {
  const response = await request.post<ApiResponseWrapper<InterviewSession>>(
    "/interview-sessions",
    { resumeProfileId, roleDirection },
    { signal },
  );
  return extractData(response.data, "/interview-sessions");
}

/** 获取会话详情（含历史问答），页面刷新后恢复用 GET /api/interview-sessions/{sessionId} */
export async function getInterviewSessionApi(
  sessionId: string,
  signal?: AbortSignal,
): Promise<InterviewSession> {
  const response = await request.get<ApiResponseWrapper<InterviewSession>>(
    `/interview-sessions/${sessionId}`,
    { signal },
  );
  return extractData(response.data, `/interview-sessions/${sessionId}`);
}

/** 查询面试会话历史列表（分页） GET /api/interview-sessions?page=&size= */
export async function listInterviewSessionsApi(
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<InterviewSessionPage> {
  const response = await request.get<ApiResponseWrapper<InterviewSessionPage>>(
    "/interview-sessions",
    { params: { page, size }, signal },
  );
  return extractData(response.data, "/interview-sessions");
}

/** 提交回答 POST /api/interview-sessions/{sessionId}/answers */
export async function submitAnswerApi(
  sessionId: string,
  questionId: string,
  answerText: string,
  requestId: string,
  durationSeconds: number,
  signal?: AbortSignal,
): Promise<SubmitAnswerData> {
  const response = await request.post<ApiResponseWrapper<SubmitAnswerData>>(
    `/interview-sessions/${sessionId}/answers`,
    { questionId, answerText, requestId, durationSeconds },
    // 只放宽本接口的超时，见 SUBMIT_ANSWER_TIMEOUT_MS 说明
    { signal, timeout: SUBMIT_ANSWER_TIMEOUT_MS },
  );
  return extractData(response.data, `/interview-sessions/${sessionId}/answers`);
}

/** 提前结束面试 POST /api/interview-sessions/{sessionId}/finish */
export async function finishInterviewSessionApi(
  sessionId: string,
  signal?: AbortSignal,
): Promise<InterviewSession> {
  const response = await request.post<ApiResponseWrapper<InterviewSession>>(
    `/interview-sessions/${sessionId}/finish`,
    undefined,
    { signal },
  );
  return extractData(response.data, `/interview-sessions/${sessionId}/finish`);
}

/** 删除面试会话（软删除，Task 7.2） DELETE /api/interview-sessions/{sessionId} */
export async function deleteInterviewSessionApi(
  sessionId: string,
  signal?: AbortSignal,
): Promise<void> {
  await request.delete<ApiResponseWrapper<null>>(
    `/interview-sessions/${sessionId}`,
    { signal },
  );
}
