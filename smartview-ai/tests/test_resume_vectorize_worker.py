import asyncio
import json
from datetime import datetime, timezone

from app.core.config import Settings
from app.core.errors import AppError
from app.workers import resume_vectorize_worker


class FakeIncomingMessage:
    """测试向量 worker 的 ACK、拒绝和发布行为，不连接真实 RabbitMQ。"""

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
        "taskId": "00000000-0000-0000-0000-000000000204",
        "traceId": "00000000-0000-0000-0000-000000000024",
        "messageType": "RESUME_VECTORIZE_TASK",
        "schemaVersion": "1.0.0",
        "retryCount": retry_count,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "resumeProfileId": "12",
        "profileVersion": 2,
    }


def _settings() -> Settings:
    return Settings(_env_file=None, rabbitmq_retry_delay_seconds=0.001)


def test_successful_vectorize_message_is_published_and_acked(monkeypatch) -> None:
    monkeypatch.setattr(
        resume_vectorize_worker,
        "vectorize_resume_profile",
        lambda profile_id, profile_version, settings=None: 6,
    )
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_vectorize_worker.handle_resume_vectorize_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "RESUME_VECTORIZE_RESULT"
    assert published[0]["success"] is True
    assert published[0]["chunksCount"] == 6
    assert published[0]["operation"] == "UPSERT"


def test_delete_vectorize_message_cleans_profile_vectors(monkeypatch) -> None:
    deleted: list[tuple[str, object]] = []

    def fake_delete(profile_id, settings=None):
        deleted.append((profile_id, settings))

    monkeypatch.setattr(
        resume_vectorize_worker,
        "delete_resume_profile_vectors",
        fake_delete,
    )
    payload = _task_payload()
    payload["operation"] = "DELETE"
    message = FakeIncomingMessage(payload)
    published: list[dict] = []

    async def publish(result: dict) -> None:
        published.append(result)

    asyncio.run(
        resume_vectorize_worker.handle_resume_vectorize_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert deleted and deleted[0][0] == "12"
    assert published[0]["success"] is True
    assert published[0]["operation"] == "DELETE"
    assert published[0]["chunksCount"] == 0


def test_retryable_vectorize_error_republishes_with_incremented_count(monkeypatch) -> None:
    def fail_vectorize(profile_id, profile_version, settings=None):
        raise AppError("Chroma 暂时不可用", code="VECTOR_STORE_UNAVAILABLE")

    monkeypatch.setattr(
        resume_vectorize_worker,
        "vectorize_resume_profile",
        fail_vectorize,
    )
    message = FakeIncomingMessage(_task_payload(retry_count=1))
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_vectorize_worker.handle_resume_vectorize_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "RESUME_VECTORIZE_TASK"
    assert published[0]["retryCount"] == 2


def test_non_retryable_vectorize_error_publishes_terminal_failure(monkeypatch) -> None:
    def fail_vectorize(profile_id, profile_version, settings=None):
        raise AppError("画像尚未确认", code="RESUME_PROFILE_NOT_CONFIRMED")

    monkeypatch.setattr(
        resume_vectorize_worker,
        "vectorize_resume_profile",
        fail_vectorize,
    )
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_vectorize_worker.handle_resume_vectorize_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "RESUME_VECTORIZE_RESULT"
    assert published[0]["success"] is False
    assert published[0]["operation"] == "UPSERT"
    assert published[0]["retryCount"] == 3


def test_invalid_vectorize_message_is_rejected_without_requeue() -> None:
    message = FakeIncomingMessage({"messageType": "INVALID"})
    published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    asyncio.run(
        resume_vectorize_worker.handle_resume_vectorize_message(
            message,
            publish,
            _settings(),
        )
    )

    assert message.rejected is True
    assert message.acked is False
    assert published == []
