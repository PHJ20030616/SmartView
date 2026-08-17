import type { components } from "../../api/generated/schema";

type InterviewReport = components["schemas"]["InterviewReport"];

/** 面试准备度等级 */
type ReadinessLevel = NonNullable<InterviewReport["readinessLevel"]>;
/** 报告状态 */
export type ReportStatus = NonNullable<InterviewReport["status"]>;
/** 面试方向 */
type RoleDirection = NonNullable<InterviewReport["roleDirection"]>;
/** 参考答案类型 */
type AnswerType = NonNullable<
  NonNullable<InterviewReport["referenceAnswers"]>[number]["answerType"]
>;

/** 准备度 → 中文标签 */
export const READINESS_LABEL: Record<ReadinessLevel, string> = {
  NOT_READY: "准备不足",
  NEEDS_PRACTICE: "需加强练习",
  READY: "已准备就绪",
  WELL_PREPARED: "准备充分",
};

/** 准备度 → 标签颜色 */
export const READINESS_COLOR: Record<ReadinessLevel, string> = {
  NOT_READY: "red",
  NEEDS_PRACTICE: "orange",
  READY: "green",
  WELL_PREPARED: "cyan",
};

/** 报告状态 → 中文标签 */
export const STATUS_LABEL: Record<ReportStatus, string> = {
  GENERATING: "生成中",
  SUCCESS: "已生成",
  FAILED: "生成失败",
};

/** 面试方向 → 中文标签 */
export const ROLE_DIRECTION_LABEL: Record<RoleDirection, string> = {
  JAVA_BACKEND: "Java 后端",
  AGENT_DEVELOPMENT: "Agent 开发",
};

/** 参考答案类型 → 中文标签 */
export const ANSWER_TYPE_LABEL: Record<AnswerType, string> = {
  BASIC_KEY_POINTS: "基础要点",
  PROJECT_STRUCTURE: "项目结构",
  SCENARIO_FRAMEWORK: "场景框架",
};
