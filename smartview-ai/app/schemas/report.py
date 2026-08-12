"""报告生成接口的 MQ 消息数据模型。

字段与契约保持一致（contracts/mq/report_generate_task.schema.json /
report_generate_result.schema.json）。报告生成不走 HTTP 同步端点，仅经 MQ。
"""

from __future__ import annotations

from datetime import datetime
from typing import Any, Literal, Self
from uuid import UUID

from pydantic import BaseModel, Field, model_validator

ReportReadiness = Literal["NOT_READY", "NEEDS_PRACTICE", "READY", "WELL_PREPARED"]
ReportAnswerType = Literal[
    "BASIC_KEY_POINTS", "PROJECT_STRUCTURE", "SCENARIO_FRAMEWORK"
]


class ReportGenerateTask(BaseModel):
    """报告生成 MQ 任务信封，字段与 contracts/mq 保持一致。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["REPORT_GENERATE_TASK"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    sessionId: str


class ReportCoverage(BaseModel):
    """三阶段覆盖率。"""

    basicCoverage: float = Field(ge=0, le=1)
    projectCoverage: float = Field(ge=0, le=1)
    scenarioCoverage: float = Field(ge=0, le=1)


class ReportReferenceAnswer(BaseModel):
    """单道题的参考答案。"""

    questionId: str
    answerType: ReportAnswerType
    referenceContent: str
    keyPoints: list[str] = Field(default_factory=list)
    tradeoffs: list[dict[str, Any]] = Field(default_factory=list)


class ReportGenerateResult(BaseModel):
    """报告生成 MQ 结果消息。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["REPORT_GENERATE_RESULT"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    sessionId: str
    success: bool
    reportId: str | None = None
    overallScore: int | None = Field(default=None, ge=0, le=100)
    readinessLevel: ReportReadiness | None = None
    roleFitScore: int | None = Field(default=None, ge=0, le=100)
    summary: str | None = None
    strengths: list[str] | None = None
    weaknesses: list[str] | None = None
    riskPoints: list[str] | None = None
    suggestions: list[dict[str, Any]] | None = None
    coverage: ReportCoverage | None = None
    referenceAnswers: list[ReportReferenceAnswer] | None = None
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_result_invariants(self) -> Self:
        """成功必须带完整报告内容，失败必须带错误原因，避免 Spring 一直显示生成中。"""
        if self.success:
            if (
                not self.reportId
                or self.overallScore is None
                or self.readinessLevel is None
                or self.roleFitScore is None
                or not (self.summary or "").strip()
                or not self.strengths
                or not self.weaknesses
                or not self.riskPoints
                or self.coverage is None
                or not self.referenceAnswers
            ):
                raise ValueError("报告生成成功时必须提供完整报告内容")
        elif not (self.errorMessage or "").strip():
            raise ValueError("报告生成失败时必须提供 errorMessage")
        return self
