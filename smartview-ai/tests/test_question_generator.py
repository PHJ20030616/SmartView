"""候选问题 LLM 生成服务测试。

覆盖：预生成目标提示词含主题、追问目标提示词只含题目/回答/期望要点（不读评估事实）、
追问类型透传、LLM 返回字段归一（缺失 topic 回退目标主题、非法来源回退）、单题失败降级。
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
    "followUpKind": "GAP",
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
    async def fake(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        return payload

    monkeypatch.setattr(qg, "call_deepseek_json", fake)


def test_non_follow_up_target_has_no_kind(monkeypatch) -> None:
    """换题/入口候选不得带 followUpKind：决策侧按"类型 + 得分"选型，多带字段会污染语义。"""
    async def fake(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        return _llm_payload(reason="")

    monkeypatch.setattr(qg, "call_deepseek_json", fake)
    result = _run(monkeypatch, _state(generation_targets=[_PRE_TARGET]))

    assert len(result) == 1
    assert result[0]["followUpKind"] is None
    assert result[0]["candidateType"] == "SAME_STAGE_SWITCH"


def test_follow_up_target_with_illegal_kind_degrades_to_none(monkeypatch) -> None:
    """非法/缺失的追问类型收敛为 None（决策侧软回退），并留下 warn 便于归因。"""
    async def fake(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        return _llm_payload(reason="")

    monkeypatch.setattr(qg, "call_deepseek_json", fake)
    broken = dict(_FOLLOW_TARGET, followUpKind="UNKNOWN")
    result = _run(monkeypatch, _state(
        pool_type="FOLLOW_UP", generation_targets=[broken],
        question_text="什么是内存模型？", answer_text="volatile 保证可见性",
    ))

    assert result[0]["followUpKind"] is None
    # 类型缺失时仍给出可追溯的生成原因（按 GAP 兜底文案）
    assert result[0]["reason"] == "针对回答缺失或含糊的要点补充提问"


def test_pre_generated_target_builds_candidate(monkeypatch) -> None:
    captured = {}

    async def fake(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
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


def test_follow_up_target_uses_answer_without_evaluation_facts(monkeypatch) -> None:
    """追问提示词只依赖题目/回答/期望要点：与评估并行时不得读取评估事实。"""
    captured = {}

    async def fake(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        captured["user"] = next(m["content"] for m in messages if m["role"] == "user")
        captured["system"] = next(m["content"] for m in messages if m["role"] == "system")
        return _llm_payload(reason="基于缺失要点追问")

    monkeypatch.setattr(qg, "call_deepseek_json", fake)

    state = _state(
        pool_type="FOLLOW_UP",
        generation_targets=[_FOLLOW_TARGET],
        question_text="什么是内存模型？",
        answer_text="volatile 保证可见性",
        expected_points=["可见性", "禁止重排"],
    )
    result = _run(monkeypatch, state)

    assert len(result) == 1
    assert "什么是内存模型？" in captured["user"]
    assert "volatile 保证可见性" in captured["user"]
    assert "可见性" in captured["user"]
    # 评估事实一律不出现在追问提示词里（得分/命中/缺失/风险/追问依据）
    for forbidden in ("得分：", "缺失要点：", "风险点：", "追问依据："):
        assert forbidden not in captured["user"]
    assert result[0]["followUpKind"] == "GAP"
    assert result[0]["reason"] == "基于缺失要点追问"


def test_follow_up_deep_kind_carries_deep_focus(monkeypatch) -> None:
    """深挖型目标的提示词带上深挖角度，与补缺口型区分开。"""
    captured = {}

    async def fake(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        captured["user"] = next(m["content"] for m in messages if m["role"] == "user")
        return _llm_payload(reason="")

    monkeypatch.setattr(qg, "call_deepseek_json", fake)

    deep_target = dict(_FOLLOW_TARGET, followUpKind="DEEP")
    result = _run(monkeypatch, _state(
        pool_type="FOLLOW_UP",
        generation_targets=[deep_target],
        question_text="什么是内存模型？",
        answer_text="volatile 保证可见性",
    ))

    assert "深挖" in captured["user"]
    assert result[0]["followUpKind"] == "DEEP"
    # reason 缺失时按追问类型兜底，便于审计
    assert result[0]["reason"] == "针对回答亮点深入追问"


def test_missing_llm_topic_falls_back_to_target_topic(monkeypatch) -> None:
    _patch_llm(monkeypatch, _llm_payload(topic=""))
    result = _run(monkeypatch, _state())
    assert result[0]["topic"] == "Java 并发"


def test_invalid_source_type_normalized(monkeypatch) -> None:
    _patch_llm(monkeypatch, _llm_payload(sourceType="UNKNOWN"))
    result = _run(monkeypatch, _state())
    assert result[0]["sourceType"] == "KNOWLEDGE_BASE"


def test_single_target_llm_failure_is_degraded(monkeypatch) -> None:
    async def failing(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        raise AppError("AI 生成服务暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(qg, "call_deepseek_json", failing)

    result = _run(monkeypatch, _state())

    # 单目标失败不阻断整体：该目标被降级丢弃
    assert result == []


def test_failed_target_does_not_block_successful_target(monkeypatch) -> None:
    call_count = 0

    async def flaky(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
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
