"""候选问题 LLM 生成服务测试。

覆盖：预生成目标提示词含主题、追问目标提示词含评估事实与依据、
LLM 返回字段归一（缺失 topic 回退目标主题、非法来源回退）、单题失败降级。
"""

import asyncio
from uuid import uuid4

import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.nodes.generate_question import generate_questions
from app.services import question_generator as qg

_settings = Settings(_env_file=None)

_PRE_TARGET = {"stage": "BASIC", "topic": "Java 并发", "candidateType": "SAME_STAGE_SWITCH"}

_FOLLOW_TARGET = {
    "stage": "BASIC",
    "topic": "Java 并发",
    "candidateType": "FOLLOW_UP",
    "basisType": "missing",
    "basis": "未说明 volatile 语义",
}


def _state(**overrides) -> dict:
    base = dict(
        session_id="1",
        role_direction="JAVA_BACKEND",
        pool_type="PRE_GENERATED",
        current_topic="Java 并发",
        history_topics=[],
        evaluation_facts=None,
        generation_targets=[_PRE_TARGET],
    )
    base.update(overrides)
    return base


def _llm_payload(**overrides) -> dict:
    payload = {
        "questionText": "请解释 volatile 的可见性语义。",
        "topic": "Java 并发",
        "sourceType": "KNOWLEDGE_BASE",
        "expectedPoints": ["可见性", "禁止重排序"],
        "targetPoint": "Java 并发",
        "reason": "覆盖必覆盖主题",
    }
    payload.update(overrides)
    return payload


def _patch_llm(monkeypatch, payload) -> None:
    async def fake(messages, settings, *, what="候选题", repair_error=None):
        return payload

    monkeypatch.setattr(qg, "call_deepseek_json", fake)


def test_pre_generated_target_builds_candidate(monkeypatch) -> None:
    captured = {}

    async def fake(messages, settings, *, what="候选题", repair_error=None):
        user = next(m["content"] for m in messages if m["role"] == "user")
        captured["user"] = user
        return _llm_payload()

    monkeypatch.setattr(qg, "call_deepseek_json", fake)

    result = _run(monkeypatch, _state())

    assert len(result) == 1
    assert result[0]["questionText"] == "请解释 volatile 的可见性语义。"
    assert result[0]["candidateType"] == "SAME_STAGE_SWITCH"
    assert result[0]["sourceType"] == "KNOWLEDGE_BASE"
    # 提示词应包含目标主题
    assert "Java 并发" in captured["user"]


def test_follow_up_target_passes_evaluation_facts(monkeypatch) -> None:
    captured = {}

    async def fake(messages, settings, *, what="候选题", repair_error=None):
        user = next(m["content"] for m in messages if m["role"] == "user")
        captured["user"] = user
        return _llm_payload(reason="基于缺失要点追问")

    monkeypatch.setattr(qg, "call_deepseek_json", fake)

    state = _state(
        pool_type="FOLLOW_UP",
        generation_targets=[_FOLLOW_TARGET],
        evaluation_facts={
            "score": 60,
            "questionText": "什么是内存模型？",
            "answerText": "……",
            "missingPoints": ["未说明 volatile 语义"],
        },
    )
    result = _run(monkeypatch, state)

    assert len(result) == 1
    assert "未说明 volatile 语义" in captured["user"]
    assert result[0]["reason"] == "基于缺失要点追问"


def test_missing_llm_topic_falls_back_to_target_topic(monkeypatch) -> None:
    _patch_llm(monkeypatch, _llm_payload(topic=""))
    result = _run(monkeypatch, _state())
    assert result[0]["topic"] == "Java 并发"


def test_invalid_source_type_normalized(monkeypatch) -> None:
    _patch_llm(monkeypatch, _llm_payload(sourceType="UNKNOWN"))
    result = _run(monkeypatch, _state())
    assert result[0]["sourceType"] == "KNOWLEDGE_BASE"


def test_single_target_llm_failure_is_degraded(monkeypatch) -> None:
    async def failing(messages, settings, *, what="候选题", repair_error=None):
        raise AppError("AI 生成服务暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(qg, "call_deepseek_json", failing)

    result = _run(monkeypatch, _state())

    # 单目标失败不阻断整体：该目标被降级丢弃
    assert result == []


def test_failed_target_does_not_block_successful_target(monkeypatch) -> None:
    call_count = 0

    async def flaky(messages, settings, *, what="候选题", repair_error=None):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            raise AppError("AI 生成服务暂时不可用", code="LLM_REQUEST_FAILED")
        return _llm_payload()

    monkeypatch.setattr(qg, "call_deepseek_json", flaky)

    # 两个目标：第一个 LLM 失败被降级丢弃，第二个成功保留 → 不阻断整体
    state = _state(generation_targets=[_PRE_TARGET, _PRE_TARGET])
    result = _run(monkeypatch, state)

    assert len(result) == 1
    assert result[0]["topic"] == "Java 并发"


def _run(monkeypatch, state: dict) -> list:
    """执行 generate_questions 节点并返回 raw_candidates。

    generate_questions 为 async 节点，需通过事件循环执行（与
    test_interview_graph 处理 async 图的方式一致）。
    """
    result = asyncio.run(generate_questions(state, _settings))
    return result["raw_candidates"]
