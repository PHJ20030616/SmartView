import { request } from "../../api/request";
import type { components } from "../../api/generated/schema";

/** 后端统一响应包装（与 interviewApi 保持一致） */
interface ApiResponseWrapper<T> {
  code: string;
  message: string;
  data: T | null;
  traceId: string;
  timestamp: string;
}

type InterviewReport = components["schemas"]["InterviewReport"];

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

/** 按会话查询报告 GET /api/interview-sessions/{sessionId}/report（面试结束页首拉入口） */
export async function getReportBySessionApi(
  sessionId: string,
  signal?: AbortSignal,
): Promise<InterviewReport> {
  const response = await request.get<ApiResponseWrapper<InterviewReport>>(
    `/interview-sessions/${sessionId}/report`,
    { signal },
  );
  return extractData(response.data, `/interview-sessions/${sessionId}/report`);
}

/** 按报告 ID 查询报告 GET /api/reports/{reportId}（轮询与直查共用） */
export async function getReportApi(
  reportId: string,
  signal?: AbortSignal,
): Promise<InterviewReport> {
  const response = await request.get<ApiResponseWrapper<InterviewReport>>(
    `/reports/${reportId}`,
    { signal },
  );
  return extractData(response.data, `/reports/${reportId}`);
}

/** 报告失败后重试生成 POST /api/reports/{reportId}/retry */
export async function retryReportApi(
  reportId: string,
  signal?: AbortSignal,
): Promise<InterviewReport> {
  const response = await request.post<ApiResponseWrapper<InterviewReport>>(
    `/reports/${reportId}/retry`,
    undefined,
    { signal },
  );
  return extractData(response.data, `/reports/${reportId}/retry`);
}
