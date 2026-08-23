"""清理 worker 的 MQ 消费行为测试（Task 7.2）。

覆盖：成功清理并回传结果、MinIO/Chroma 可重试错误重投任务队列、
确定性错误回传终态失败、坏消息处理。
"""

import asyncio
import json
from datetime import datetime, timezone

from app.core.config import Settings
from app.core.errors import AppError
from app.workers import cleanup_worker


class FakeIncomingMessage:
    """测试清理 worker 的 ACK、拒绝行为，不连接真实 RabbitMQ。"""

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
        "taskId": "00000000-0000-0000-0000-000000000301",
        "traceId": "00000000-0000-0000-0000-000000000031",
        "messageType": "CLEANUP_TASK",
        "schemaVersion": "1.0.0",
        "retryCount": retry_count,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "bizType": "RESUME_FILE",
        "bizId": "88",
        "objectKey": "resumes/7/old-resume.pdf",
        "resumeProfileIds": ["101", "102"],
    }


def _settings() -> Settings:
    return Settings(_env_file=None, rabbitmq_retry_delay_seconds=0.001)


def test_successful_cleanup_is_published_and_acked(monkeypatch) -> None:
    monkeypatch.setattr(
        cleanup_worker,
        "cleanup_resume_resources",
        lambda object_key, profile_ids, settings=None: len(profile_ids),
    )
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        cleanup_worker.handle_cleanup_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "CLEANUP_RESULT"
    assert published[0]["success"] is True
    assert published[0]["cleanedProfileCount"] == 2
    assert published[0]["bizId"] == "88"
    assert task_published == []


def test_retryable_cleanup_error_republishes_task_to_task_queue(monkeypatch) -> None:
    def fail_cleanup(object_key, profile_ids, settings=None):
        raise AppError("MinIO 暂时不可用", code="MINIO_UNAVAILABLE")

    monkeypatch.setattr(cleanup_worker, "cleanup_resume_resources", fail_cleanup)
    message = FakeIncomingMessage(_task_payload(retry_count=1))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        cleanup_worker.handle_cleanup_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    # 重试任务消息必须走任务队列发布回调，绝不能混入结果队列
    assert published == []
    assert task_published[0]["messageType"] == "CLEANUP_TASK"
    assert task_published[0]["retryCount"] == 2
    assert task_published[0]["objectKey"] == "resumes/7/old-resume.pdf"


def test_retryable_error_exhausted_publishes_final_failure(monkeypatch) -> None:
    def fail_cleanup(object_key, profile_ids, settings=None):
        raise AppError("向量库暂时不可用", code="VECTOR_STORE_UNAVAILABLE")

    monkeypatch.setattr(cleanup_worker, "cleanup_resume_resources", fail_cleanup)
    message = FakeIncomingMessage(_task_payload(retry_count=3))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        cleanup_worker.handle_cleanup_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert task_published == []
    assert published[0]["success"] is False
    assert published[0]["errorMessage"] == "向量库暂时不可用"
    assert published[0]["messageType"] == "CLEANUP_RESULT"


def test_deterministic_error_publishes_immediate_failure(monkeypatch) -> None:
    def fail_cleanup(object_key, profile_ids, settings=None):
        raise AppError("业务类型不支持", code="BUSINESS_ERROR")

    monkeypatch.setattr(cleanup_worker, "cleanup_resume_resources", fail_cleanup)
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        cleanup_worker.handle_cleanup_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    # 确定性错误不重试，直接回传终态失败
    assert task_published == []
    assert published[0]["success"] is False
    assert published[0]["errorMessage"] == "业务类型不支持"


def test_invalid_message_with_task_fields_publishes_failure(monkeypatch) -> None:
    payload = _task_payload()
    payload.pop("objectKey")  # 缺少必填字段（objectKey 必填），消息格式无效
    message = FakeIncomingMessage(payload)
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(result: dict) -> None:
        published.append(result)

    async def publish_task(result: dict) -> None:
        task_published.append(result)

    asyncio.run(
        cleanup_worker.handle_cleanup_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert published[0]["success"] is False
    assert published[0]["messageType"] == "CLEANUP_RESULT"
    assert "格式无效" in published[0]["errorMessage"]
    assert task_published == []


def test_cleanup_with_empty_profile_ids_only_deletes_minio(monkeypatch) -> None:
    """解析失败的简历没有画像：仅删除 MinIO 对象，向量清理数量为 0。"""
    cleaned: list[tuple] = []

    def fake_cleanup(object_key, profile_ids, settings=None):
        cleaned.append((object_key, profile_ids))
        return len(profile_ids)

    monkeypatch.setattr(cleanup_worker, "cleanup_resume_resources", fake_cleanup)
    payload = _task_payload()
    payload["resumeProfileIds"] = []
    message = FakeIncomingMessage(payload)
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(result: dict) -> None:
        published.append(result)

    async def publish_task(result: dict) -> None:
        task_published.append(result)

    asyncio.run(
        cleanup_worker.handle_cleanup_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert cleaned == [("resumes/7/old-resume.pdf", [])]
    assert message.acked is True
    assert published[0]["success"] is True
    assert published[0]["cleanedProfileCount"] == 0
    assert task_published == []


def test_ungroupable_invalid_message_is_rejected() -> None:
    # 完全缺少 taskId/bizId 的消息无法关联任务，只能拒绝并进入 DLQ
    message = FakeIncomingMessage({"messageType": "CLEANUP_TASK"})
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(result: dict) -> None:
        published.append(result)

    async def publish_task(result: dict) -> None:
        task_published.append(result)

    asyncio.run(
        cleanup_worker.handle_cleanup_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.rejected is True
    assert message.acked is False
    assert published == []
    assert task_published == []
