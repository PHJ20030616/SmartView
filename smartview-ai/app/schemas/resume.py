"""简历解析接口的请求、响应和结构化数据模型。"""

from datetime import datetime
from typing import Literal, Self
from uuid import UUID

from pydantic import BaseModel, Field, HttpUrl, model_validator


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


class ResumeParseResult(ParseResumeResponse):
    """简历解析 MQ 结果信封，保留结构化简历字段并携带任务元数据。"""

    taskId: UUID
    traceId: UUID
    messageType: Literal["RESUME_PARSE_RESULT"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    resumeFileId: str
