/**
 * LLM 调用观测的展示映射。
 *
 * 枚举值来自 contracts/web-api/openapi.yaml，映射表集中在此处，
 * 避免在页面里散落中文字面量导致文案不一致。
 */

/** 调用场景 → 中文标签 */
export const SCENE_LABEL: Record<string, string> = {
  question_generate: "出题",
  evaluate: "回答评估",
  report_generate: "报告与参考答案",
  profile_analyze: "画像分析",
  resume_parse: "简历结构化",
};

/** 调用结果 → 中文标签 */
export const STATUS_LABEL: Record<string, string> = {
  SUCCESS: "成功",
  FAILED: "失败",
};

/** 调用结果 → 标签颜色 */
export const STATUS_COLOR: Record<string, string> = {
  SUCCESS: "success",
  FAILED: "error",
};

/**
 * 失败错误码 → 中文人话说明。
 *
 * 看板的使用者是运维与开发：只显示 LLM_REQUEST_REJECTED 这样的机器码，
 * 排查时还得回头翻代码；这里补一句"该怎么办"，把可行动信息直接放到页面上。
 */
export const ERROR_CODE_HINT: Record<string, string> = {
  LLM_REQUEST_FAILED: "请求未送达或超时，可按瞬时故障重试",
  LLM_RATE_LIMITED: "上游限流（HTTP 429），退避后重试有效",
  LLM_UPSTREAM_ERROR: "上游服务故障（HTTP 5xx），退避后重试有效",
  LLM_REQUEST_REJECTED: "请求被上游拒绝（模型名/鉴权/会话配置错误），重试无效，需检查配置",
  LLM_OUTPUT_TRUNCATED: "输出达到最大 token 被截断，需缩小单次请求内容或提高上限",
  LLM_INVALID_JSON: "模型未返回合法 JSON，需检查提示词或提高输出上限",
  REPORT_REFERENCE_VALIDATION_FAILED: "参考答案校验失败，缺题或字段不合法",
  REPORT_LLM_VALIDATION_FAILED: "报告评语校验失败，字段不满足契约",
  RESUME_PARSE_SCHEMA_INVALID: "简历结构不符合契约，可能是扫描件或格式异常",
  LLM_NOT_CONFIGURED: "未配置模型密钥，AI 能力不可用",
};

/** 上游停止原因 → 中文标签 */
export const FINISH_REASON_LABEL: Record<string, string> = {
  stop: "正常结束",
  length: "输出被截断",
  content_filter: "内容被过滤",
  tool_calls: "工具调用",
};

/** 业务对象类型 → 中文标签 */
export const BIZ_TYPE_LABEL: Record<string, string> = {
  interview_session: "面试会话",
  resume_file: "简历文件",
  resume_profile: "简历画像",
};

/**
 * 判断一次失败是否值得重试（与后端 worker 的重试白名单保持一致）。
 *
 * 看板把"可重试"与"确定性失败"分开显示，避免运维对永远不会成功的请求反复触发重试。
 */
const RETRYABLE_ERROR_CODES = new Set([
  "LLM_REQUEST_FAILED",
  "LLM_RATE_LIMITED",
  "LLM_UPSTREAM_ERROR",
  "LLM_INVALID_JSON",
]);

export function isRetryableError(errorCode?: string | null): boolean {
  return errorCode != null && RETRYABLE_ERROR_CODES.has(errorCode);
}
