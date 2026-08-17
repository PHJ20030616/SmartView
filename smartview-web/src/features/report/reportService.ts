import type { components } from "../../api/generated/schema";
import { getReportApi, getReportBySessionApi, retryReportApi } from "./reportApi";

type InterviewReport = components["schemas"]["InterviewReport"];

/** 报告轮询配置：3 秒间隔、最长 3 分钟（LLM 生成本身可能耗时较长） */
const REPORT_POLL_INTERVAL_MS = 3000;
const REPORT_POLL_MAX_DURATION_MS = 180_000;

/**
 * 报告操作错误：携带 HTTP 状态码与超时标记。
 * timeout=true 表示轮询超过最长时限仍未终态，页面据此提示「稍后刷新查看」。
 */
export class ReportError extends Error {
  readonly status?: number;
  readonly timeout: boolean;

  constructor(message: string, status?: number, timeout = false) {
    super(message);
    this.name = "ReportError";
    this.status = status;
    this.timeout = timeout;
  }
}

/** 把未知错误收敛为 ReportError，优先取后端 message 便于页面直接展示 */
export function toReportError(error: unknown, fallback: string): ReportError {
  if (error instanceof ReportError) {
    return error;
  }
  if (typeof error === "object" && error !== null && "isAxiosError" in error) {
    const typed = error as {
      isAxiosError: boolean;
      message?: string;
      response?: { status?: number; data?: { message?: string } };
    };
    const status = typed.response?.status;
    const message = typed.response?.data?.message ?? typed.message ?? fallback;
    return new ReportError(message, status);
  }
  return new ReportError(error instanceof Error ? error.message : fallback);
}

/** 按会话查询报告（进入报告页首拉） */
export async function fetchReportBySession(
  sessionId: string,
  signal?: AbortSignal,
): Promise<InterviewReport> {
  return getReportBySessionApi(sessionId, signal);
}

/** 按报告 ID 查询报告 */
export async function fetchReport(
  reportId: string,
  signal?: AbortSignal,
): Promise<InterviewReport> {
  return getReportApi(reportId, signal);
}

/** 报告失败后重试生成，返回重试后报告（status=GENERATING） */
export async function retryReport(
  reportId: string,
  signal?: AbortSignal,
): Promise<InterviewReport> {
  return retryReportApi(reportId, signal);
}

/**
 * 轮询报告直到非 GENERATING 终态（SUCCESS/FAILED）；3 分钟超时抛 ReportError(timeout)。
 * 页面离开 / 主动取消时经 signal 中止，抛出稳定 AbortError（name="AbortError"）。
 */
export async function waitForReport(
  reportId: string,
  signal?: AbortSignal,
): Promise<InterviewReport> {
  const deadline = Date.now() + REPORT_POLL_MAX_DURATION_MS;
  const deadlineController = new AbortController();
  const handleExternalAbort = () => deadlineController.abort();
  const timeoutId = setTimeout(
    () => deadlineController.abort(),
    REPORT_POLL_MAX_DURATION_MS,
  );
  signal?.addEventListener("abort", handleExternalAbort, { once: true });

  let last: InterviewReport | undefined;
  try {
    last = await getReportApi(reportId, deadlineController.signal);
    if (isTerminal(last.status)) {
      return last;
    }
    while (true) {
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        break;
      }
      // 剩余时间不足一个轮询周期时按剩余时间等待，避免超时后仍多发一次请求
      await sleep(Math.min(REPORT_POLL_INTERVAL_MS, remaining), deadlineController.signal);
      last = await getReportApi(reportId, deadlineController.signal);
      if (isTerminal(last.status)) {
        return last;
      }
    }
    throw new ReportError("报告生成时间较长，可稍后刷新查看", undefined, true);
  } catch (error) {
    if (signal?.aborted) {
      throw createAbortError();
    }
    if (deadlineController.signal.aborted) {
      throw new ReportError("报告生成时间较长，可稍后刷新查看", undefined, true);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
    signal?.removeEventListener("abort", handleExternalAbort);
  }
}

/** 终态判定：SUCCESS / FAILED 后不再轮询 */
function isTerminal(status: InterviewReport["status"] | undefined): boolean {
  return status === "SUCCESS" || status === "FAILED";
}

/** 可被 signal 中止的延时：中止时抛 AbortError，避免轮询挂起 */
function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    let timeoutId: ReturnType<typeof setTimeout>;
    const cleanup = () => signal?.removeEventListener("abort", handleAbort);
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

/** 稳定的取消错误（name=AbortError，调用方据此识别主动取消） */
function createAbortError(): Error {
  const error = new Error("报告轮询已取消");
  error.name = "AbortError";
  return error;
}
