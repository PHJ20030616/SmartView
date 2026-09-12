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
