"""画像分析接口的请求、响应和 MQ 消息数据模型。

字段与契约保持一致（contracts/ai-api/openapi.yaml 的 AnalyzeProfileRequest /
AnalyzeProfileResponse，以及 contracts/mq/profile_analyze_task.schema.json /
profile_analyze_result.schema.json）。
"""

from datetime import datetime
from typing import Literal, Self
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

RoleDirection = Literal["JAVA_BACKEND", "AGENT_DEVELOPMENT"]


class SkillTag(BaseModel):
    """技能标签。"""

    skill: str
    level: Literal["EXPERT", "PROFICIENT", "FAMILIAR", "BASIC"] | None = None
    source: Literal["WORK", "PROJECT", "EDUCATION"] | None = None


class ProjectNode(BaseModel):
    """项目关系图谱中的单个项目节点。"""

    projectName: str | None = None
    techStack: list[str] = Field(default_factory=list)
    responsibilities: list[str] = Field(default_factory=list)
    highlights: list[str] = Field(default_factory=list)


class ProjectGraph(BaseModel):
    """项目关系图谱。"""

    projects: list[ProjectNode] = Field(default_factory=list)


class CapabilityHints(BaseModel):
    """能力线索：工程能力、系统设计能力、领域能力。"""

    engineering: list[str] = Field(default_factory=list)
    architecture: list[str] = Field(default_factory=list)
    domain: list[str] = Field(default_factory=list)


class RiskPoint(BaseModel):
    """风险点。"""

    category: Literal[
        "VAGUE_DESCRIPTION", "SHALLOW_DEPTH", "OUTDATED_TECH", "LACK_EVIDENCE"
    ] | None = None
    description: str


class StageTargets(BaseModel):
    """阶段覆盖目标：八股、项目追问、场景题的重点。"""

    basic: list[str] = Field(default_factory=list)
    project: list[str] = Field(default_factory=list)
    scenario: list[str] = Field(default_factory=list)


class ProfileAnalysis(BaseModel):
    """画像分析结果（内部面试准备材料，用于生成阶段计划和出题策略）。"""

    skillTags: list[SkillTag] = Field(default_factory=list)
    projectGraph: ProjectGraph = Field(default_factory=ProjectGraph)
    capabilityHints: CapabilityHints = Field(default_factory=CapabilityHints)
    riskPoints: list[RiskPoint] = Field(default_factory=list)
    suggestedTopics: list[str] = Field(default_factory=list)
    stageTargets: StageTargets = Field(default_factory=StageTargets)
    modelName: str | None = None
    modelVersion: str | None = None

    @model_validator(mode="after")
    def validate_meaningful_content(self) -> Self:
        """空结果不能视为一次成功的画像分析。

        契约只校验字段存在（success=true 必须带 skillTags），空数组同样能平凡通过；
        这里在内容层面要求至少产出技能标签和建议主题，空/不完整结果会进入
        profile_analyzer 的修复重试路径，而不是以 SUCCESS 落库。
        """
        if not self.skillTags:
            raise ValueError("画像分析必须产出至少一个技能标签（skillTags）")
        if not self.suggestedTopics:
            raise ValueError("画像分析必须产出至少一个建议面试主题（suggestedTopics）")
        return self


class AnalyzeProfileRequest(BaseModel):
    """画像分析 HTTP 请求（Spring Boot → FastAPI）。"""

    resumeProfileId: str
    roleDirection: RoleDirection
    traceId: UUID


class AnalyzeProfileResponse(BaseModel):
    """画像分析 HTTP 响应。"""

    success: bool
    skillTags: list[SkillTag] | None = None
    projectGraph: ProjectGraph | None = None
    capabilityHints: CapabilityHints | None = None
    riskPoints: list[RiskPoint] | None = None
    suggestedTopics: list[str] | None = None
    stageTargets: StageTargets | None = None
    modelName: str | None = None
    modelVersion: str | None = None
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_response_invariants(self) -> Self:
        """成功必须携带分析结果，失败必须携带错误原因。"""
        if self.success and self.skillTags is None:
            raise ValueError("分析成功时必须提供 skillTags")
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("分析失败时必须提供 errorMessage")
        return self


class ProfileAnalyzeTask(BaseModel):
    """画像分析 MQ 任务信封，字段与 contracts/mq 保持一致。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["PROFILE_ANALYZE_TASK"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    resumeProfileId: str
    roleDirection: RoleDirection
    profileVersion: int = Field(ge=1)
    vectorizeCompleted: bool


class ProfileAnalyzeResult(BaseModel):
    """画像分析 MQ 结果消息。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["PROFILE_ANALYZE_RESULT"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    resumeProfileId: str
    profileVersion: int = Field(ge=1)
    roleDirection: RoleDirection
    success: bool
    skillTags: list[SkillTag] | None = None
    projectGraph: ProjectGraph | None = None
    capabilityHints: CapabilityHints | None = None
    riskPoints: list[RiskPoint] | None = None
    suggestedTopics: list[str] | None = None
    stageTargets: StageTargets | None = None
    modelName: str | None = None
    modelVersion: str | None = None
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_result_invariants(self) -> Self:
        """成功必须带非空技能标签，失败必须带错误原因，避免 Spring 一直显示处理中。"""
        if self.success and not self.skillTags:
            raise ValueError("画像分析成功时必须提供非空 skillTags")
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("画像分析失败时必须提供 errorMessage")
        return self
