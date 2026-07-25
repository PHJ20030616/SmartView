import { request } from "../../api/request";
import type {
  ResumeFile,
  ResumeProfile,
  UpdateResumeProfileRequest,
} from "./resumeTypes";

/**
 * 简历相关 API 客户端
 * 基于 Axios 实例，自动携带 JWT Token 和 Trace ID
 *
 * 注意：这是临时 API 客户端，后续应从 OpenAPI 契约通过 openapi-typescript-fetch 生成
 */

/** 后端统一响应包装 */
interface ApiResponseWrapper<T> {
  code: string;
  message: string;
  data: T | null;
  traceId: string;
  timestamp: string;
}

/**
 * 安全提取响应数据，null 时抛出明确错误
 * 避免使用非空断言 `!`，防止后端返回 data:null 时前端静默崩溃
 */
function extractData<T>(wrapper: ApiResponseWrapper<T>, endpoint: string): T {
  if (wrapper.data == null) {
    throw new Error(`接口 ${endpoint} 返回数据为空`);
  }
  return wrapper.data;
}

/**
 * 上传简历 PDF 文件
 * POST /api/resumes
 */
export async function uploadResumeApi(
  file: File,
  signal?: AbortSignal,
): Promise<ResumeFile> {
  const formData = new FormData();
  formData.append("file", file);

  const response = await request.post<
    ApiResponseWrapper<ResumeFile>
  >("/resumes", formData, {
    headers: { "Content-Type": "multipart/form-data" },
    signal,
  });

  return extractData(response.data, "/resumes");
}

/**
 * 查询简历文件状态（用于轮询解析进度）
 * GET /api/resumes/{resumeFileId}
 */
export async function getResumeFileApi(
  resumeFileId: string,
  signal?: AbortSignal,
): Promise<ResumeFile> {
  const response = await request.get<
    ApiResponseWrapper<ResumeFile>
  >(`/resumes/${resumeFileId}`, { signal });

  return extractData(response.data, `/resumes/${resumeFileId}`);
}

/**
 * 获取简历画像详情
 * GET /api/resume-profiles/{profileId}
 */
export async function getResumeProfileApi(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeProfile> {
  const response = await request.get<
    ApiResponseWrapper<ResumeProfile>
  >(`/resume-profiles/${profileId}`, { signal });

  return extractData(response.data, `/resume-profiles/${profileId}`);
}

/**
 * 更新简历画像关键字段
 * PUT /api/resume-profiles/{profileId}
 */
export async function updateResumeProfileApi(
  profileId: string,
  data: UpdateResumeProfileRequest,
  signal?: AbortSignal,
): Promise<ResumeProfile> {
  const response = await request.put<
    ApiResponseWrapper<ResumeProfile>
  >(`/resume-profiles/${profileId}`, data, { signal });

  return extractData(response.data, `/resume-profiles/${profileId}`);
}

/**
 * 确认简历画像
 * POST /api/resume-profiles/{profileId}/confirm
 */
export async function confirmResumeProfileApi(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeProfile> {
  const response = await request.post<
    ApiResponseWrapper<ResumeProfile>
  >(`/resume-profiles/${profileId}/confirm`, undefined, { signal });

  return extractData(response.data, `/resume-profiles/${profileId}/confirm`);
}
