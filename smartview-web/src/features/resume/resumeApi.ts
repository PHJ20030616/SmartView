import { request } from "../../api/request";
import type {
  ProfileAnalysisStatus,
  ResumeFile,
  ResumeFilePage,
  ResumeProfile,
  ResumeVectorizationStatus,
  RoleDirection,
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
 * 查询简历历史列表（分页）
 * GET /api/resumes?page=&size=
 */
export async function getResumeHistoryApi(
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<ResumeFilePage> {
  const response = await request.get<ApiResponseWrapper<ResumeFilePage>>(
    "/resumes",
    { params: { page, size }, signal },
  );

  return extractData(response.data, "/resumes");
}

/**
 * 删除简历文件（软删除，Task 7.2）
 * DELETE /api/resumes/{resumeFileId}
 *
 * 删除成功后业务列表不再展示该简历；MinIO 文件与 Chroma 向量由后台
 * CLEANUP 任务异步清理，无需前端等待。
 */
export async function deleteResumeApi(
  resumeFileId: string,
  signal?: AbortSignal,
): Promise<void> {
  await request.delete<ApiResponseWrapper<null>>(
    `/resumes/${resumeFileId}`,
    { signal },
  );
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

/**
 * 查询简历向量入库状态
 * GET /api/resume-profiles/{profileId}/vectorization
 */
export async function getResumeVectorizationStatusApi(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeVectorizationStatus> {
  const response = await request.get<
    ApiResponseWrapper<ResumeVectorizationStatus>
  >(`/resume-profiles/${profileId}/vectorization`, { signal });

  return extractData(
    response.data,
    `/resume-profiles/${profileId}/vectorization`,
  );
}

/**
 * 重试简历向量入库
 * POST /api/resume-profiles/{profileId}/vectorization/retry
 */
export async function retryResumeVectorizationApi(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeVectorizationStatus> {
  const response = await request.post<
    ApiResponseWrapper<ResumeVectorizationStatus>
  >(`/resume-profiles/${profileId}/vectorization/retry`, undefined, { signal });

  return extractData(
    response.data,
    `/resume-profiles/${profileId}/vectorization/retry`,
  );
}

/**
 * 触发或获取方向画像分析（幂等）
 * POST /api/profile-analyses
 */
export async function startProfileAnalysisApi(
  profileId: string,
  roleDirection: RoleDirection,
  signal?: AbortSignal,
): Promise<ProfileAnalysisStatus> {
  const response = await request.post<ApiResponseWrapper<ProfileAnalysisStatus>>(
    "/profile-analyses",
    { profileId, roleDirection },
    { signal },
  );

  return extractData(response.data, "/profile-analyses");
}

/**
 * 查询画像分析状态（前端轮询用）
 * GET /api/profile-analyses/{profileId}?roleDirection=
 */
export async function getProfileAnalysisStatusApi(
  profileId: string,
  roleDirection: RoleDirection,
  signal?: AbortSignal,
): Promise<ProfileAnalysisStatus> {
  const response = await request.get<ApiResponseWrapper<ProfileAnalysisStatus>>(
    `/profile-analyses/${profileId}`,
    { params: { roleDirection }, signal },
  );

  return extractData(response.data, `/profile-analyses/${profileId}`);
}

/**
 * 重试方向画像分析
 * POST /api/profile-analyses/{profileId}/retry?roleDirection=
 */
export async function retryProfileAnalysisApi(
  profileId: string,
  roleDirection: RoleDirection,
  signal?: AbortSignal,
): Promise<ProfileAnalysisStatus> {
  const response = await request.post<ApiResponseWrapper<ProfileAnalysisStatus>>(
    `/profile-analyses/${profileId}/retry`,
    undefined,
    { params: { roleDirection }, signal },
  );

  return extractData(
    response.data,
    `/profile-analyses/${profileId}/retry`,
  );
}
