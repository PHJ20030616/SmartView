"""清理任务 MQ 消息模型（Task 7.2 软删除与物理清理）。

字段与 contracts/mq/cleanup_task.schema.json / cleanup_result.schema.json 保持一致。
"""

from datetime import datetime
from typing import Any, Literal, Self

from pydantic import BaseModel, Field, field_validator, model_validator


class CleanupTask(BaseModel):
    """清理任务消息：删除 MinIO 对象与对应画像的全部 Chroma 向量。"""

    taskId: str
    traceId: str
    messageType: Literal["CLEANUP_TASK"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    bizType: Literal["RESUME_FILE"]
    bizId: str
    objectKey: str = Field(min_length=1)
    # 可为空：简历解析失败无画像时，仅清理 MinIO 文件，不删除任何向量
    resumeProfileIds: list[str] = Field(default_factory=list)

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


class CleanupResult(BaseModel):
    """清理任务结果消息：回传清理状态供 Spring 更新 ai_task 终态与审计。"""

    taskId: str
    traceId: str
    messageType: Literal["CLEANUP_RESULT"]
    schemaVersion: Literal["1.0.0"]
    retryCount: int = Field(ge=0, le=3)
    createdAt: datetime
    bizType: Literal["RESUME_FILE"]
    bizId: str
    success: bool
    cleanedProfileCount: int | None = Field(default=None, ge=0)
    errorMessage: str | None = None

    @model_validator(mode="after")
    def validate_result_invariants(self) -> Self:
        """成功必须带清理数量，失败必须带原因，避免 Spring 无法判断清理终态。"""
        if self.success and self.cleanedProfileCount is None:
            raise ValueError("清理成功时必须提供 cleanedProfileCount")
        if not self.success and not (self.errorMessage or "").strip():
            raise ValueError("清理失败时必须提供 errorMessage")
        return self
