import type { components } from "../../api/generated/schema";

/**
 * 简历相关类型统一从 OpenAPI 生成结果派生。
 * 跨服务字段必须以 contracts/web-api/openapi.yaml 为唯一来源，
 * 避免前端手写类型与后端响应逐渐漂移。
 */
export type ResumeProfile = components["schemas"]["ResumeProfile"];
export type ResumeFile = components["schemas"]["ResumeFile"];
export type UpdateResumeProfileRequest =
  components["schemas"]["UpdateResumeProfileRequest"];

/** 解析状态 */
export type ParseStatus = ResumeFile["parseStatus"];

/** 确认状态 */
export type ConfirmStatus = ResumeProfile["confirmStatus"];
