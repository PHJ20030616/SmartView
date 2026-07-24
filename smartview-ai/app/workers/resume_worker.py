"""简历解析消息任务处理器。"""

from datetime import datetime, timezone
from typing import Any

from app.core.errors import AppError
from app.schemas.resume import ResumeParseResult, ResumeParseTask
from app.services.resume_parser import parse_resume


def _serialize_result(result: ResumeParseResult) -> dict[str, Any]:
    """输出可直接发布到 MQ 的 JSON 数据，并移除契约中不允许为 null 的可选字段。"""
    return result.model_dump(mode="json", exclude_none=True)


async def process_resume_parse_task(payload: dict[str, Any]) -> dict[str, Any]:
    """校验 MQ 信封并复用与 HTTP API 相同的 LangGraph 解析流程。

    解析异常在达到最大重试次数前继续向消息框架抛出，以便触发统一重试；
    最后一次失败则返回符合结果契约的失败消息，避免消息无限重投。
    """
    task = ResumeParseTask.model_validate(payload)
    try:
        response = await parse_resume(
            file_url=str(task.fileUrl),
            mime_type=task.mimeType,
            trace_id=str(task.traceId),
            raise_on_error=True,
        )
    except AppError as exc:
        if task.retryCount < 3:
            raise
        return _serialize_result(
            ResumeParseResult(
                taskId=task.taskId,
                traceId=task.traceId,
                messageType="RESUME_PARSE_RESULT",
                schemaVersion="1.0.0",
                retryCount=task.retryCount,
                createdAt=datetime.now(timezone.utc),
                resumeFileId=task.resumeFileId,
                success=False,
                rawText="",
                errorMessage=exc.message,
            )
        )

    return _serialize_result(
        ResumeParseResult(
            taskId=task.taskId,
            traceId=task.traceId,
            messageType="RESUME_PARSE_RESULT",
            schemaVersion="1.0.0",
            retryCount=task.retryCount,
            createdAt=datetime.now(timezone.utc),
            resumeFileId=task.resumeFileId,
            **response.model_dump(),
        )
    )
