"""报告生成 worker MQ 行为测试：确认、有界重试、结果发布、坏消息处理。"""

import asyncio
import json
from datetime import datetime, timezone

from app.core.config import Settings
from app.core.errors import AppError
from app.schemas.report import ReportGenerateResult
from app.workers import report_worker


class FakeIncomingMessage:
    """测试报告 worker 的 ACK、拒绝和发布行为，不连接真实 RabbitMQ。"""

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
        "taskId": "00000000-0000-0000-0000-000000000501",
        "traceId": "00000000-0000-0000-0000-000000000051",
        "messageType": "REPORT_GENERATE_TASK",
        "schemaVersion": "1.0.0",
        "retryCount": retry_count,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sessionId": "88",
    }


def _settings() -> Settings:
    return Settings(_env_file=None, rabbitmq_retry_delay_seconds=0.001)


def _content() -> ReportGenerateResult:
    return ReportGenerateResult(
        reportId="5",
        overallScore=72,
        readinessLevel="READY",
        roleFitScore=80,
        summary="总体评价",
        strengths=["基础扎实"],
        weaknesses=["深度不足"],
        riskPoints=["项目描述空泛"],
        suggestions=[{"topic": "并发", "reason": "薄弱", "resources": []}],
        coverage={"basicCoverage": 0.8, "projectCoverage": 1.0, "scenarioCoverage": 0.5},
        referenceAnswers=[
            {"questionId": "10", "answerType": "BASIC_KEY_POINTS",
             "referenceContent": "参考答案", "keyPoints": ["要点"], "tradeoffs": []}
        ],
    )


def _run(message, publish, publish_task, settings=None):
    async def runner():
        await report_worker.handle_report_generate_message(
            message, publish, settings or _settings(), publish_task
        )

    asyncio.run(runner())


def test_successful_report_is_published_and_acked(monkeypatch) -> None:
    # 注意：monkeypatch 替换的是类方法 generate，fake 必须接收 self。
    async def fake_generate(self, session_id, *, settings=None):
        return _content()

    monkeypatch.setattr(report_worker.ReportGenerator, "generate", fake_generate)
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    _run(message, publish, publish_task)

    assert message.acked is True
    assert published[0]["messageType"] == "REPORT_GENERATE_RESULT"
    assert published[0]["success"] is True
    assert published[0]["reportId"] == "5"
    assert published[0]["overallScore"] == 72
    assert published[0]["referenceAnswers"][0]["questionId"] == "10"
    assert task_published == []


def test_deterministic_error_publishes_terminal_failure(monkeypatch) -> None:
    async def fail_generate(self, session_id, *, settings=None):
        raise AppError("会话没有已回答问题，无法生成报告", code="NO_ANSWERED_QUESTION")

    monkeypatch.setattr(report_worker.ReportGenerator, "generate", fail_generate)
    message = FakeIncomingMessage(_task_payload())
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    _run(message, publish, publish_task)

    assert message.acked is True
    assert published[0]["success"] is False
    assert published[0]["retryCount"] == 0
    assert "已回答问题" in published[0]["errorMessage"]
    assert task_published == []


def test_retryable_llm_error_republishes_task(monkeypatch) -> None:
    async def fail_generate(self, session_id, *, settings=None):
        raise AppError("LLM 服务暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(report_worker.ReportGenerator, "generate", fail_generate)
    message = FakeIncomingMessage(_task_payload(retry_count=1))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    _run(message, publish, publish_task)

    assert message.acked is True
    assert published == []
    assert task_published[0]["messageType"] == "REPORT_GENERATE_TASK"
    assert task_published[0]["retryCount"] == 2


def test_retry_exhausted_publishes_terminal_failure(monkeypatch) -> None:
    async def fail_generate(self, session_id, *, settings=None):
        raise AppError("LLM 服务暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(report_worker.ReportGenerator, "generate", fail_generate)
    message = FakeIncomingMessage(_task_payload(retry_count=3))
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    _run(message, publish, publish_task)

    assert message.acked is True
    assert published[0]["success"] is False
    assert published[0]["retryCount"] == 3
    assert task_published == []


def test_invalid_task_message_is_rejected_without_requeue() -> None:
    message = FakeIncomingMessage({"messageType": "INVALID"})
    published: list[dict] = []
    task_published: list[dict] = []

    async def publish(payload: dict) -> None:
        published.append(payload)

    async def publish_task(payload: dict) -> None:
        task_published.append(payload)

    _run(message, publish, publish_task)

    assert message.rejected is True
    assert message.acked is False
    assert published == []
    assert task_published == []
