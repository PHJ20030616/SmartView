import asyncio
import json
from datetime import datetime, timezone

from app.core.config import Settings
from app.core.errors import AppError
from app.schemas.profile import ProfileAnalysis
from app.workers import profile_worker


class FakeIncomingMessage:
    """测试画像分析 worker 的 ACK、拒绝和发布行为，不连接真实 RabbitMQ。"""

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


def _task_payload(retry_count: int = 0, vectorize_completed: bool = True) -> dict:
    return {
        "taskId": "00000000-0000-0000-0000-000000000301",
        "traceId": "00000000-0000-0000-0000-000000000031",
        "messageType": "PROFILE_ANALYZE_TASK",
        "schemaVersion": "1.0.0",
        "retryCount": retry_count,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "resumeProfileId": "12",
        "roleDirection": "JAVA_BACKEND",
        "profileVersion": 2,
        "vectorizeCompleted": vectorize_completed,
    }


def _settings() -> Settings:
    return Settings(_env_file=None, rabbitmq_retry_delay_seconds=0.001)


def _analysis() -> ProfileAnalysis:
    return ProfileAnalysis(
        skillTags=[
            {"skill": "Java", "level": "EXPERT", "source": "PROJECT"}
        ],
        suggestedTopics=["并发", "JVM"],
        riskPoints=[{"category": "VAGUE_DESCRIPTION", "description": "项目描述空泛"}],
        stageTargets={"basic": ["八股重点"], "project": ["项目追问"], "scenario": ["场景题"]},
        # 生产环境由 profile_analyzer 回填生成模型信息
        modelName="deepseek-v4-flash",
        modelVersion="1.0.0",
    )


def test_successful_analyze_message_is_published_and_acked(monkeypatch) -> None:
    async def fake_analyze(profile_id, profile_version, role_direction, *, settings=None):
        return _analysis()

    monkeypatch.setattr(profile_worker, "analyze_profile", fake_analyze)
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        profile_worker.handle_profile_analyze_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "PROFILE_ANALYZE_RESULT"
    assert published[0]["success"] is True
    assert published[0]["skillTags"][0]["skill"] == "Java"
    assert published[0]["roleDirection"] == "JAVA_BACKEND"
    assert published[0]["profileVersion"] == 2
    assert published[0]["modelName"] == "deepseek-v4-flash"
    assert task_published == []


def test_vectorize_not_completed_publishes_terminal_failure(monkeypatch) -> None:
    called: list[tuple] = []

    async def fake_analyze(profile_id, profile_version, role_direction, *, settings=None):
        called.append((profile_id, profile_version, role_direction))
        return _analysis()

    monkeypatch.setattr(profile_worker, "analyze_profile", fake_analyze)
    message = FakeIncomingMessage(_task_payload(vectorize_completed=False))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        profile_worker.handle_profile_analyze_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert called == []
    assert published[0]["success"] is False
    assert "向量尚未入库" in published[0]["errorMessage"]
    assert task_published == []


def test_retryable_llm_error_republishes_task_to_task_queue(monkeypatch) -> None:
    async def fail_analyze(profile_id, profile_version, role_direction, *, settings=None):
        raise AppError("LLM 服务暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(profile_worker, "analyze_profile", fail_analyze)
    message = FakeIncomingMessage(_task_payload(retry_count=1))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        profile_worker.handle_profile_analyze_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    # 重试任务消息必须走任务队列发布回调，绝不能混入结果队列
    assert published == []
    assert task_published[0]["messageType"] == "PROFILE_ANALYZE_TASK"
    assert task_published[0]["retryCount"] == 2


def test_non_retryable_error_publishes_terminal_failure(monkeypatch) -> None:
    async def fail_analyze(profile_id, profile_version, role_direction, *, settings=None):
        raise AppError("简历画像不存在或已删除", code="RESUME_PROFILE_NOT_FOUND")

    monkeypatch.setattr(profile_worker, "analyze_profile", fail_analyze)
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        profile_worker.handle_profile_analyze_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "PROFILE_ANALYZE_RESULT"
    assert published[0]["success"] is False
    # 确定性失败如实回传 task.retryCount（未经重试为 0），不伪造为最大重试次数
    assert published[0]["retryCount"] == 0
    assert task_published == []


def test_unexpected_exception_republishes_task_to_task_queue(monkeypatch) -> None:
    """未预期异常（如 MySQL/网络故障）同样走有界重试，且必须回任务队列。"""

    async def crash(profile_id, profile_version, role_direction, *, settings=None):
        raise RuntimeError("MySQL 连接中断")

    monkeypatch.setattr(profile_worker, "analyze_profile", crash)
    message = FakeIncomingMessage(_task_payload(retry_count=0))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        profile_worker.handle_profile_analyze_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert published == []
    assert task_published[0]["messageType"] == "PROFILE_ANALYZE_TASK"
    assert task_published[0]["retryCount"] == 1


def test_retry_exhausted_publishes_terminal_failure(monkeypatch) -> None:
    """retryCount 达到上限后必须回传终态失败，不能再发布重试消息。"""

    async def fail_analyze(profile_id, profile_version, role_direction, *, settings=None):
        raise AppError("LLM 服务暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(profile_worker, "analyze_profile", fail_analyze)
    # rabbitmq_task_max_retries 默认 3，retryCount=3 表示已用尽
    message = FakeIncomingMessage(_task_payload(retry_count=3))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        profile_worker.handle_profile_analyze_message(
            message,
            publish,
            _settings(),
            publish_task,
        )
    )

    assert message.acked is True
    assert published[0]["messageType"] == "PROFILE_ANALYZE_RESULT"
    assert published[0]["success"] is False
    assert published[0]["retryCount"] == 3
    assert task_published == []


def test_invalid_analyze_message_is_rejected_without_requeue() -> None:
    message = FakeIncomingMessage({"messageType": "INVALID"})
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    asyncio.run(
        profile_worker.handle_profile_analyze_message(
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
