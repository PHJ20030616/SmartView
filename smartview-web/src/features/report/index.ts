/**
 * 报告 feature 统一出口。
 *
 * 页面只从本模块导入服务与展示映射，禁止直接依赖 reportApi 之外的 HTTP 细节，
 * 便于单测 mock 与后续接口演进。
 */
export {
  fetchReport,
  fetchReportBySession,
  ReportError,
  retryReport,
  toReportError,
  waitForReport,
} from "./reportService";
export {
  getReportApi,
  getReportBySessionApi,
  retryReportApi,
} from "./reportApi";
export {
  ANSWER_TYPE_LABEL,
  READINESS_COLOR,
  READINESS_LABEL,
  ROLE_DIRECTION_LABEL,
  STATUS_LABEL,
} from "./reportTypes";
export type { ReportStatus } from "./reportTypes";
