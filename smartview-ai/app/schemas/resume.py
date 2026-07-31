"""简历解析接口的请求、响应和结构化数据模型。"""

from datetime import datetime
from typing import Any, Literal, Self
from uuid import UUID

from pydantic import BaseModel, Field, HttpUrl, field_validator, model_validator


class ContactInfo(BaseModel):
    """候选人的联系方式。"""

    phone: str | None = None
    email: str | None = None
    location: str | None = None


class EducationExperience(BaseModel):
    """候选人的教育经历。"""

    school: str | None = None
    degree: str | None = None
    major: str | None = None
    startDate: str | None = None
    endDate: str | None = None


class WorkExperience(BaseModel):
    """候选人的工作经历。"""

    company: str | None = None
    position: str | None = None
    startDate: str | None = None
    endDate: str | None = None
    description: str | None = None


class ProjectExperience(BaseModel):
    """候选人的项目经历。"""

    projectName: str | None = None
    role: str | None = None
    description: str | None = None
    techStack: list[str] = Field(default_factory=list)


class ResumeStructuredData(BaseModel):
    """LLM 输出的结构化简历数据。"""

    candidateName: str | None = None
    contactInfo: ContactInfo | None = None
    education: list[EducationExperience] = Field(default_factory=list)
    workExperience: list[WorkExperience] = Field(default_factory=list)
    projectExperience: list[ProjectExperience] = Field(default_factory=list)
    skills: list[str] = Field(default_factory=list)
    rawText: str | None = None


class ParseResumeRequest(BaseModel):
    """简历解析请求。"""

    fileUrl: HttpUrl
    mimeType: Literal["application/pdf"]
    traceId: UUID


class ParseResumeResponse(ResumeStructuredData):
    """简历解析响应。"""

    success: bool
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_response_invariants(self) -> Self:
        """成功和失败响应分别携带调用方需要的最小信息。"""
        if self.success and not (self.rawText or "").strip():
            raise ValueError("解析成功时必须提供非空 rawText")
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("解析失败时必须提供 errorMessage")
        return self


class ResumeParseTask(BaseModel):
    """简历解析 MQ 任务信封，字段与 contracts/mq 保持一致。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["RESUME_PARSE_TASK"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    fileUrl: HttpUrl
    mimeType: str
    resumeFileId: str

    @field_validator("createdAt", mode="before")
    @classmethod
    def normalize_java_local_datetime(cls, value: Any) -> Any:
        """兼容 Spring Jackson 将 LocalDateTime 编码成数组的历史消息格式。

        契约规定的标准格式仍然是 ISO 8601 字符串；这里仅在消费边界兼容
        ``[年, 月, 日, 时, 分, 秒, 纳秒]``，避免旧消息因服务版本不一致被
        直接拒绝。纳秒需要截断到 Python datetime 支持的微秒精度。
        """
        if not isinstance(value, (list, tuple)) or len(value) not in (6, 7):
            return value

        try:
            year, month, day, hour, minute, second = value[:6]
            nanosecond = value[6] if len(value) == 7 else 0
            return datetime(
                int(year),
                int(month),
                int(day),
                int(hour),
                int(minute),
                int(second),
                microsecond=int(nanosecond) // 1_000,
            )
        except (TypeError, ValueError, OverflowError):
            # 保留原值交给 Pydantic 生成标准字段校验错误，避免吞掉坏消息。
            return value


class ResumeParseResult(ParseResumeResponse):
    """简历解析 MQ 结果信封，保留结构化简历字段并携带任务元数据。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["RESUME_PARSE_RESULT"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    resumeFileId: str


class ResumeVectorizeTask(BaseModel):
    """简历向量入库任务消息，与 MQ JSON Schema 保持一致。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["RESUME_VECTORIZE_TASK"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    resumeProfileId: str
    profileVersion: int = Field(ge=1)
    # 旧版本消息没有 operation 时按 UPSERT 处理，保证滚动发布期间消息仍可消费。
    operation: Literal["UPSERT", "DELETE"] = "UPSERT"


class ResumeVectorizeResult(BaseModel):
    """简历向量入库结果消息。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["RESUME_VECTORIZE_RESULT"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    resumeProfileId: str
    profileVersion: int = Field(ge=1)
    operation: Literal["UPSERT", "DELETE"] = "UPSERT"
    success: bool
    chunksCount: int | None = Field(default=None, ge=0)
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_result_invariants(self) -> Self:
        """失败必须带原因，成功必须返回实际切片数量，避免 Spring 一直显示处理中。"""
        if self.success and self.chunksCount is None:
            raise ValueError("向量入库成功时必须提供 chunksCount")
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("向量入库失败时必须提供 errorMessage")
        return self
