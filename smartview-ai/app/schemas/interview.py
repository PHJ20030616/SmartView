"""面试流程 Pydantic 模型，字段与 contracts/ai-api/openapi.yaml 面试接口一致。

说明：项目现状为手写 Pydantic schema（与 profile.py / resume.py 一致），
字段命名与 ai-api 契约一一对应；后续若启用契约代码生成到 app/generated/，
本文件可整体替换为生成产物。
"""

from __future__ import annotations

from typing import Literal
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
