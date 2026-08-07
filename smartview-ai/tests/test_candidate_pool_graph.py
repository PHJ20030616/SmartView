"""候选池生成 LangGraph 端到端测试（替身 LLM）。

覆盖：预生成池封顶与元数据完整、追问池封顶为 2、与历史主题去重、LLM 异常整体失败。
"""

import asyncio
from uuid import uuid4

from app.core.config import Settings
from app.graphs.candidate_pool_graph import CandidatePoolGraph
from app.schemas.interview import (
    GenerateCandidatePoolRequest,
    StagePlan,
    StagePlanStage,
)
from app.services import question_generator as qg

_settings = Settings(_env_file=None)

_PLAN = StagePlan(
    policy_version="1.0",
    total_min_questions=7,
    total_max_questions=20,
    stages=[
        StagePlanStage(
            stage="BASIC",
            min_questions=3,
            max_questions=8,
            required_topics=["Java 并发", "JVM", "Spring"],
            max_follow_up_depth=2,
        ),
        StagePlanStage(
            stage="PROJECT",
            min_questions=2,
            max_questions=6,
            required_topics=["电商平台"],
            max_follow_up_depth=3,
        ),
    ],
)


def _request(**overrides) -> GenerateCandidatePoolRequest:
    base = dict(
        sessionId="1",
        questionId="11",
        roleDirection="JAVA_BACKEND",
        poolType="PRE_GENERATED",
        currentStage="BASIC",
        stagePlan=_PLAN,
        traceId=uuid4(),
    )
    base.update(overrides)
    return GenerateCandidatePoolRequest(**base)


def _payload_for(topic: str) -> dict:
    """按主题返回替身 LLM 输出。"""
    return {
        "questionText": f"关于{topic}的问题。",
        "topic": topic,
        "sourceType": "KNOWLEDGE_BASE",
        "expectedPoints": ["要点1"],
        "targetPoint": topic,
    }


def _stub_llm(monkeypatch) -> None:
    async def fake(messages, settings, *, what="候选题", repair_error=None):
        user = next(m["content"] for m in messages if m["role"] == "user")
        # 从用户提示词中提取主题行，按目标主题生成对应内容
        topic = "Java 并发"
        for line in user.splitlines():
            if line.startswith("主题：") or line.startswith("追问主题："):
                topic = line.split("：", 1)[1].strip() or topic
        return _payload_for(topic)

    monkeypatch.setattr(qg, "call_deepseek_json", fake)


def test_pre_generated_pool_capped_at_four(monkeypatch) -> None:
    _stub_llm(monkeypatch)
    graph = CandidatePoolGraph(_settings)

    resp = asyncio.run(graph.generate(_request()))

    assert resp.success is True
    # 同阶段 2（Java 并发、JVM）+ 下一阶段 1（电商平台）≤ 4
    assert len(resp.candidates) == 3
    assert len(resp.candidates) <= 4
    # 候选元数据完整：类型、阶段、来源、目标考察点
    for item in resp.candidates:
        assert item.candidateType in ("SAME_STAGE_SWITCH", "NEXT_STAGE_ENTRY")
        assert item.stage
        assert item.sourceType
        assert item.targetPoint


def test_pre_generated_dedup_by_history_topic(monkeypatch) -> None:
    _stub_llm(monkeypatch)
    graph = CandidatePoolGraph(_settings)

    resp = asyncio.run(
        graph.generate(_request(historyTopics=["Java 并发"]))
    )

    # 已问主题 Java 并发被剔除，同阶段仅剩 JVM
    topics = [c.topic for c in resp.candidates]
    assert "Java 并发" not in topics
    assert "JVM" in topics


def test_follow_up_pool_capped_at_two(monkeypatch) -> None:
    _stub_llm(monkeypatch)
    graph = CandidatePoolGraph(_settings)

    resp = asyncio.run(
        graph.generate(
            _request(
                poolType="FOLLOW_UP",
                sessionContext={"currentTopic": "Java 并发"},
                evaluationFacts={
                    "score": 60,
                    "missingPoints": ["未说明 volatile 语义"],
                    "riskPoints": [{"category": "SHALLOW_DEPTH", "description": "空泛"}],
                },
            )
        )
    )

    assert resp.success is True
    assert 1 <= len(resp.candidates) <= 2
    assert all(c.candidateType == "FOLLOW_UP" for c in resp.candidates)


def test_follow_up_no_targets_returns_success_with_empty_pool(monkeypatch) -> None:
    _stub_llm(monkeypatch)
    graph = CandidatePoolGraph(_settings)

    resp = asyncio.run(
        graph.generate(
            _request(
                poolType="FOLLOW_UP",
                sessionContext={"currentTopic": "Java 并发"},
                evaluationFacts={"score": 30},  # 得分 < 40：stage_controller 不产生目标
            )
        )
    )

    # 无生成目标不是失败：空池也返回 success=true，避免误判为 LLM 整体不可用
    assert resp.success is True
    assert resp.candidates == []


def test_llm_error_returns_failure_response(monkeypatch) -> None:
    from app.core.errors import AppError

    async def failing(messages, settings, *, what="候选题", repair_error=None):
        raise AppError("AI 生成服务暂时不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(qg, "call_deepseek_json", failing)
    graph = CandidatePoolGraph(_settings)

    resp = asyncio.run(graph.generate(_request()))

    assert resp.success is False
    assert "候选池生成失败" in resp.errorMessage
