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
    { signal },
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
