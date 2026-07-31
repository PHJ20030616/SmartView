import asyncio
import json
from datetime import datetime, timezone

import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.schemas.resume import ResumeParseTask
from app.workers import resume_worker


class FakeIncomingMessage:
    """测试 MQ 消息确认行为，不连接真实 RabbitMQ。"""

    def __init__(self, payload: dict) -> None:
        self.body = json.dumps(payload).encode("utf-8")
        self.acked = False
        self.rejected = False
        self.nacked = False

    async def ack(self) -> None:
        self.acked = True

    async def reject(self, *, requeue: bool) -> None:
        self.rejected = not requeue

    async def nack(self, *, requeue: bool) -> None:
        self.nacked = requeue


def _task_payload(retry_count: int = 0) -> dict:
    return {
        "taskId": "00000000-0000-0000-0000-000000000104",
        "traceId": "00000000-0000-0000-0000-000000000004",
        "messageType": "RESUME_PARSE_TASK",
        "schemaVersion": "1.0.0",
        "retryCount": retry_count,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "fileUrl": "https://minio.example.com/resume.pdf",
        "mimeType": "application/pdf",
        "resumeFileId": "resume-file-1",
    }


def _settings() -> Settings:
    return Settings(_env_file=None, rabbitmq_retry_delay_seconds=0.001)


def test_build_amqp_url_uses_spring_compatible_connection_fields() -> None:
    settings = Settings(
        _env_file=None,
        rabbitmq_host="rabbitmq",
        rabbitmq_port=5672,
        rabbitmq_username="smart view",
        rabbitmq_password="p@ssword",
        rabbitmq_vhost="smart/view",
    )

    assert resume_worker.build_amqp_url(settings) == (
        "amqp://smart%20view:p%40ssword@rabbitmq:5672/smart%2Fview"
    )


def test_spring_local_datetime_array_is_accepted() -> None:
    payload = _task_payload()
    payload["createdAt"] = [2026, 7, 28, 9, 19, 17, 996876000]

    task = ResumeParseTask.model_validate(payload)

    assert task.createdAt == datetime(2026, 7, 28, 9, 19, 17, 996876)


def test_successful_message_is_published_and_acked(monkeypatch) -> None:
    async def fake_process(payload):
        return {
            **payload,
            "messageType": "RESUME_PARSE_RESULT",
            "success": True,
        }

    monkeypatch.setattr(
        resume_worker,
        "process_resume_parse_task",
        fake_process,
    )
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_worker.handle_resume_parse_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert message.rejected is False
    assert published[0]["messageType"] == "RESUME_PARSE_RESULT"


def test_retryable_parse_error_republishes_with_incremented_retry_count(monkeypatch) -> None:
    async def fake_process(payload):
        raise AppError("模型暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(
        resume_worker,
        "process_resume_parse_task",
        fake_process,
    )
    message = FakeIncomingMessage(_task_payload(retry_count=1))
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_worker.handle_resume_parse_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert published[0]["retryCount"] == 2
    assert published[0]["messageType"] == "RESUME_PARSE_TASK"


@pytest.mark.parametrize(
    ("error_code", "error_message"),
    [
        ("RESUME_URL_NOT_ALLOWED", "简历文件地址不在允许的存储地址范围内"),
        ("RESUME_DOWNLOAD_CLIENT_ERROR", "简历文件下载失败，请检查文件地址是否有效"),
    ],
)
def test_non_retryable_parse_error_publishes_terminal_failure(
    monkeypatch,
    error_code: str,
    error_message: str,
) -> None:
    async def fake_process(payload):
        raise AppError(error_message, code=error_code)

    monkeypatch.setattr(
        resume_worker,
        "process_resume_parse_task",
        fake_process,
    )
    message = FakeIncomingMessage(_task_payload(retry_count=0))
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_worker.handle_resume_parse_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "RESUME_PARSE_RESULT"
    assert published[0]["retryCount"] == 3
    assert published[0]["success"] is False
    assert published[0]["errorMessage"] == error_message


def test_invalid_message_is_rejected_without_requeue() -> None:
    message = FakeIncomingMessage({"messageType": "INVALID"})
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_worker.handle_resume_parse_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.rejected is True
    assert message.acked is False
    assert published == []


def test_invalid_message_with_task_identifiers_publishes_failure_result() -> None:
    payload = _task_payload()
    payload.pop("mimeType")
    message = FakeIncomingMessage(payload)
    published: list[dict] = []

    async def publish(result: dict) -> None:
        published.append(result)

    asyncio.run(
        resume_worker.handle_resume_parse_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert message.rejected is False
    assert len(published) == 1
    assert published[0]["taskId"] == payload["taskId"]
    assert published[0]["traceId"] == payload["traceId"]
    assert published[0]["messageType"] == "RESUME_PARSE_RESULT"
    assert published[0]["schemaVersion"] == "1.0.0"
    assert published[0]["retryCount"] == 0
    assert published[0]["resumeFileId"] == payload["resumeFileId"]
    assert published[0]["success"] is False
    assert published[0]["rawText"] == ""
    assert published[0]["errorMessage"] == "简历解析任务消息格式无效，请重新上传简历"
    assert isinstance(published[0]["createdAt"], str)
