import type {
  ResumeFile,
  ResumeProfile,
  ResumeVectorizationStatus,
  UpdateResumeProfileRequest,
} from "./resumeTypes";
import {
  confirmResumeProfileApi,
  getResumeFileApi,
  getResumeProfileApi,
  getResumeVectorizationStatusApi,
  retryResumeVectorizationApi,
  updateResumeProfileApi,
  uploadResumeApi,
} from "./resumeApi";

/**
 * 简历服务层
 * 封装简历相关的业务逻辑，供页面组件调用
 */

/** 轮询阶段回调：upload（上传完成，开始轮询）/ success / failure / timeout */
export type ParsePhase = "upload" | "parse" | "success" | "failure" | "timeout";

/** 轮询配置 */
const POLL_INTERVAL_MS = 2000; // 轮询间隔：2 秒
const POLL_MAX_DURATION_MS = 120_000; // 最大轮询时长：2 分钟
const VECTOR_POLL_MAX_DURATION_MS = 60_000; // 向量入库最多等待 60 秒

/** 判断异步上传/轮询是否因页面离开而取消，供页面静默忽略该异常。 */
export function isResumeParseAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

/**
 * 上传简历文件并等待解析完成
 * 上传后自动轮询解析状态，解析成功或失败时返回
 *
 * @param file      PDF 简历文件
 * @param onPhase   可选，阶段变化回调，用于页面更新 UI 状态
 * @returns 解析成功时返回简历文件信息，失败时抛出错误
 */
export async function uploadAndWaitForParse(
  file: File,
  onPhase?: (phase: ParsePhase) => void,
  signal?: AbortSignal,
): Promise<ResumeFile> {
  try {
    throwIfAborted(signal);
    const resumeFile = await uploadResumeApi(file, signal);
    throwIfAborted(signal);
    // 通知调用方：上传已完成，进入解析阶段
    onPhase?.("parse");

    // 如果已经成功，直接返回
    if (resumeFile.parseStatus === "SUCCESS") {
      return resumeFile;
    }
    // 如果已失败，抛出错误
    if (resumeFile.parseStatus === "FAILED") {
      throw new Error(resumeFile.errorMessage || "简历解析失败，请重试");
    }

    // 轮询等待解析完成
    const startTime = Date.now();
    while (Date.now() - startTime < POLL_MAX_DURATION_MS) {
      await sleep(POLL_INTERVAL_MS, signal);

      const updated = await getResumeFileApi(resumeFile.id, signal);
      throwIfAborted(signal);

      if (updated.parseStatus === "SUCCESS") {
        return updated;
      }
      if (updated.parseStatus === "FAILED") {
        throw new Error(updated.errorMessage || "简历解析失败，请重试");
      }
      // PENDING 或 PROCESSING 状态继续轮询
    }

    throw new Error("简历解析超时，请稍后重试");
  } catch (error) {
    // Axios 取消异常的具体类型可能随版本变化，统一转换为稳定的 AbortError。
    if (signal?.aborted) {
      throw createAbortError();
    }
    throw error;
  }
}

/**
 * 查询简历文件状态
 */
export async function checkResumeStatus(resumeFileId: string): Promise<ResumeFile> {
  return getResumeFileApi(resumeFileId);
}

/**
 * 获取简历画像详情
 */
export async function fetchResumeProfile(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeProfile> {
  return getResumeProfileApi(profileId, signal);
}

/**
 * 更新简历画像关键字段
 */
export async function saveResumeProfile(
  profileId: string,
  data: UpdateResumeProfileRequest,
  signal?: AbortSignal,
): Promise<ResumeProfile> {
  return updateResumeProfileApi(profileId, data, signal);
}

/**
 * 确认简历画像
 */
export async function submitResumeConfirmation(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeProfile> {
  return confirmResumeProfileApi(profileId, signal);
}

/** 向量入库轮询终态错误，保留最近一次后端状态供页面显示重试入口。 */
export class ResumeVectorizationError extends Error {
  readonly status?: ResumeVectorizationStatus;
  readonly timedOut: boolean;

  constructor(
    message: string,
    status?: ResumeVectorizationStatus,
    timedOut = false,
  ) {
    super(message);
    this.name = "ResumeVectorizationError";
    this.status = status;
    this.timedOut = timedOut;
  }
}

/**
 * 查询当前画像的向量入库状态。
 */
export async function fetchResumeVectorizationStatus(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeVectorizationStatus> {
  return getResumeVectorizationStatusApi(profileId, signal);
}

/**
 * 轮询向量入库状态，成功才返回；失败或 60 秒超时交给页面展示重试入口。
 */
export async function waitForResumeVectorization(
  profileId: string,
  onStatus?: (status: ResumeVectorizationStatus) => void,
  signal?: AbortSignal,
): Promise<ResumeVectorizationStatus> {
  const deadline = Date.now() + VECTOR_POLL_MAX_DURATION_MS;
  const deadlineController = new AbortController();
  const handleExternalAbort = () => deadlineController.abort();
  const timeoutId = setTimeout(
    () => deadlineController.abort(),
    VECTOR_POLL_MAX_DURATION_MS,
  );
  signal?.addEventListener("abort", handleExternalAbort, { once: true });

  let status: ResumeVectorizationStatus | undefined;
  try {
    status = await getResumeVectorizationStatusApi(
        profileId,
        deadlineController.signal,
    );
    throwIfDeadlineExceeded(deadline, deadlineController.signal);
    onStatus?.(status);

    while (true) {
      if (status.status === "SUCCESS") {
        return status;
      }
      if (status.status === "FAILED") {
        throw new ResumeVectorizationError(
          status.errorMessage || "简历向量入库失败，请重试",
          status,
        );
      }

      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        break;
      }
      await sleep(
        Math.min(POLL_INTERVAL_MS, remaining),
        deadlineController.signal,
      );
      throwIfDeadlineExceeded(deadline, deadlineController.signal);
      status = await getResumeVectorizationStatusApi(
        profileId,
        deadlineController.signal,
      );
      throwIfDeadlineExceeded(deadline, deadlineController.signal);
      onStatus?.(status);
    }

    throw new ResumeVectorizationError(
      "简历向量入库等待超时，请点击重试",
      status,
      true,
    );
  } catch (error) {
    if (signal?.aborted) {
      throw createAbortError();
    }
    if (deadlineController.signal.aborted) {
      throw new ResumeVectorizationError(
        "简历向量入库等待超时，请点击重试",
        status,
        true,
      );
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
    signal?.removeEventListener("abort", handleExternalAbort);
  }
}

/**
 * 创建新的向量入库任务。隔离字段由 Spring Boot 根据当前登录用户生成，
 * 页面只传画像路径参数。
 */
export async function retryResumeVectorization(
  profileId: string,
  signal?: AbortSignal,
): Promise<ResumeVectorizationStatus> {
  return retryResumeVectorizationApi(profileId, signal);
}

/**
 * 延迟工具函数
 */
function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    let timeoutId: ReturnType<typeof setTimeout>;

    const cleanup = () => {
      signal?.removeEventListener("abort", handleAbort);
    };
    const handleAbort = () => {
      clearTimeout(timeoutId);
      cleanup();
      reject(createAbortError());
    };

    timeoutId = setTimeout(() => {
      cleanup();
      resolve();
    }, ms);
    signal?.addEventListener("abort", handleAbort, { once: true });

    if (signal?.aborted) {
      handleAbort();
    }
  });
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    throw createAbortError();
  }
}

function throwIfDeadlineExceeded(
  deadline: number,
  deadlineSignal: AbortSignal,
): void {
  if (deadlineSignal.aborted || Date.now() >= deadline) {
    throw new ResumeVectorizationError(
      "简历向量入库等待超时，请点击重试",
      undefined,
      true,
    );
  }
}

function createAbortError(): Error {
  const error = new Error("简历解析已取消");
  error.name = "AbortError";
  return error;
}
