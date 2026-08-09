"""面试流程 Pydantic 模型，字段与 contracts/ai-api/openapi.yaml 面试接口一致。

说明：项目现状为手写 Pydantic schema（与 profile.py / resume.py 一致），
字段命名与 ai-api 契约一一对应；后续若启用契约代码生成到 app/generated/，
本文件可整体替换为生成产物。
"""

from __future__ import annotations

from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

# 面试方向与问题类型/来源类型，取值与 ai-api 契约、interview_question 表枚举一致
RoleDirection = Literal["JAVA_BACKEND", "AGENT_DEVELOPMENT"]
QuestionType = Literal["OPENING", "FOLLOW_UP", "SWITCH_TOPIC", "STAGE_ENTRY"]
SourceType = Literal["KNOWLEDGE_BASE", "EXPERIENCE_CASE", "RESUME_PROJECT", "MIXED"]


class StagePlanStage(BaseModel):
    """单个阶段的计划项（snake_case，与 docs/interview-policy.md 2.2 一致）。"""

    stage: str | None = None
    min_questions: int | None = None
    max_questions: int | None = None
    required_topics: list[str] = Field(default_factory=list)
    max_follow_up_depth: int | None = None
    switch_conditions: str | None = None


class StagePlan(BaseModel):
    """Spring 端确定性生成的阶段计划（不透明 payload，按 snake_case 解析）。

    Pydantic 默认忽略未声明字段，因此对 Spring 未来新增的计划字段天然兼容。
    """

    policy_version: str | None = None
    total_min_questions: int | None = None
    total_max_questions: int | None = None
    stages: list[StagePlanStage] = Field(default_factory=list)


class GenerateFirstQuestionRequest(BaseModel):
    """首题生成 HTTP 请求（Spring Boot → FastAPI）。"""

    sessionId: str
    roleDirection: RoleDirection
    stagePlan: StagePlan
    resumeProfileId: str
    profileVersion: int
    traceId: UUID


class KnowledgeRef(BaseModel):
    """引用的八股知识片段（溯源用）。"""

    title: str | None = None
    category: str | None = None
    snippet: str | None = None


class CaseRef(BaseModel):
    """引用的面经案例（溯源用）。"""

    title: str | None = None
    scenario: str | None = None
    snippet: str | None = None


class QuestionResponse(BaseModel):
    """首题生成 HTTP 响应。"""

    success: bool
    questionText: str | None = None
    topic: str | None = None
    questionType: QuestionType | None = None
    sourceType: SourceType | None = None
    expectedPoints: list[str] = Field(default_factory=list)
    knowledgeRefs: list[KnowledgeRef] = Field(default_factory=list)
    caseRefs: list[CaseRef] = Field(default_factory=list)
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_response_invariants(self) -> QuestionResponse:
        """成功必须带题目与主题，失败必须带错误原因。"""
        if self.success and not (self.questionText and self.topic):
            raise ValueError("生成成功时必须提供 questionText 与 topic")
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("生成失败时必须提供 errorMessage")
        return self


CandidatePoolType = Literal["PRE_GENERATED", "FOLLOW_UP"]
CandidateType = Literal["SAME_STAGE_SWITCH", "NEXT_STAGE_ENTRY", "FOLLOW_UP"]


class SessionContext(BaseModel):
    """会话上下文：当前阶段/主题、已提问数量与覆盖度。

    currentStage/stageCoverage 供回答评估与追问深度门控使用；
    预生成候选池请求（GenerateCandidatePoolRequest）不携带二者，字段保持可选。
    """

    currentStage: str | None = None
    currentTopic: str | None = None
    questionCount: int | None = None
    stageCoverage: dict[str, Any] = Field(default_factory=dict)


class EvaluateAnswerRequest(BaseModel):
    """回答评估 HTTP 请求（Spring Boot → FastAPI）。

    questionText/expectedPoints 是评估对照依据；stagePlan 用于追问深度门控，
    均由 Spring 端携带当前题目信息透传，与 ai-api 契约一致。
    """

    sessionId: str
    questionId: str
    answerText: str
    roleDirection: RoleDirection
    questionText: str
    expectedPoints: list[str] = Field(default_factory=list)
    stagePlan: StagePlan = Field(default_factory=StagePlan)
    sessionContext: SessionContext = Field(default_factory=SessionContext)
    traceId: UUID


class EvaluateAnswerResponse(BaseModel):
    """回答评估 HTTP 响应：评估事实 + 追问候选池（0-2 道，完整 CandidatePoolItem）。"""

    success: bool
    score: int | None = None
    level: str | None = None
    matchedPoints: list[str] = Field(default_factory=list)
    missingPoints: list[str] = Field(default_factory=list)
    riskPoints: list[dict[str, Any]] = Field(default_factory=list)
    followUpCandidates: list[CandidatePoolItem] = Field(default_factory=list)
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_response_invariants(self) -> "EvaluateAnswerResponse":
        """成功必须带得分，失败必须带错误原因。"""
        if self.success and self.score is None:
            raise ValueError("评估成功时必须提供 score")
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("评估失败时必须提供 errorMessage")
        return self


class EvaluationFacts(BaseModel):
    """回答评估事实（追问候选池生成输入）。

    对应 ai-api 契约 GenerateCandidatePoolRequest.evaluationFacts，
    字段与 EvaluateAnswerResponse 的评估结果一致，5.4 接入 evaluate 时直接回填。
    """

    score: int | None = None
    level: str | None = None
    matchedPoints: list[str] = Field(default_factory=list)
    missingPoints: list[str] = Field(default_factory=list)
    riskPoints: list[dict[str, Any]] = Field(default_factory=list)
    answerText: str | None = None
    questionText: str | None = None


class GenerateCandidatePoolRequest(BaseModel):
    """候选池生成 HTTP 请求（Spring Boot → FastAPI）。

    poolType 决定生成目标：PRE_GENERATED 生成同阶段换题 + 下一阶段入口；
    FOLLOW_UP 基于 evaluationFacts 生成 0-2 道追问。候选池不决定下一步。
    """

    sessionId: str
    questionId: str
    roleDirection: RoleDirection
    poolType: CandidatePoolType
    currentStage: str | None = None
    # stagePlan 由 Spring 端确定性生成并原样透传，契约中为必填，故此处不可省略
    stagePlan: StagePlan
    stageCoverage: dict[str, Any] = Field(default_factory=dict)
    sessionContext: SessionContext = Field(default_factory=SessionContext)
    evaluationFacts: EvaluationFacts | None = None
    historyTopics: list[str] = Field(default_factory=list)
    traceId: UUID


class CandidatePoolItem(BaseModel):
    """候选池中的一道候选题（契约 CandidatePoolItem）。"""

    questionText: str
    topic: str
    stage: str
    candidateType: CandidateType
    sourceType: SourceType | None = None
    expectedPoints: list[str] = Field(default_factory=list)
    targetPoint: str | None = None
    reason: str | None = None


class GenerateCandidatePoolResponse(BaseModel):
    """候选池生成 HTTP 响应。"""

    success: bool
    candidates: list[CandidatePoolItem] = Field(default_factory=list)
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_response_invariants(self) -> GenerateCandidatePoolResponse:
        """失败必须带错误原因，保证调用方能给出可读提示。"""
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("生成失败时必须提供 errorMessage")
        return self

