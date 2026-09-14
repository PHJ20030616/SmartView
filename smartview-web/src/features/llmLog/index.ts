/**
 * LLM 调用观测 feature 统一出口。
 *
 * 页面只从本模块导入，禁止直接依赖 llmLogApi 之外的 HTTP 细节，
 * 便于单测 mock 与后续接口演进。
 */
export { fetchLlmCalls, LlmLogError, toLlmLogError } from "./llmLogService";
export { listLlmCallsApi } from "./llmLogApi";
export type { LlmCallQuery } from "./llmLogApi";
export {
  BIZ_TYPE_LABEL,
  ERROR_CODE_HINT,
  FINISH_REASON_LABEL,
  isRetryableError,
  SCENE_LABEL,
  STATUS_COLOR,
  STATUS_LABEL,
} from "./llmLogTypes";
