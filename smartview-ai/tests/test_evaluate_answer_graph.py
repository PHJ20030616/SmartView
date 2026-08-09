"""回答评估图测试：评估事实 + 追问候选（替身 LLM/评估器）。"""
import asyncio
from uuid import uuid4

from app.core.config import Settings
from app.graphs.evaluate_answer_graph import EvaluateAnswerGraph
from app.schemas.interview import (
    EvaluateAnswerRequest,
    SessionContext,
    StagePlan,
    StagePlanStage,
)
from app.services import answer_evaluator as ae
from app.services import question_generator as qg

_settings = Settings(_env_file=None)

_PLAN = StagePlan(
    policy_version="1.0",
    total_min_questions=7,
    total_max_questions=20,
    stages=[
        StagePlanStage(stage="BASIC", min_questions=3, max_questions=8,
                       required_topics=["Java 并发"], max_follow_up_depth=2),
    ],
)


def _request(answer_text="volatile 保证可见性与禁止重排", **overrides):
    base = dict(
        sessionId="1",
        questionId="11",
        answerText=answer_text,
        roleDirection="JAVA_BACKEND",
        questionText="volatile 的作用？",
        expectedPoints=["可见性"],
        stagePlan=_PLAN,
        sessionContext=SessionContext(
            currentStage="BASIC", currentTopic="Java 并发", questionCount=2,
            stageCoverage={"BASIC": {"question_count": 1, "current_topic_follow_up_count": 0}},
        ),
        traceId=uuid4(),
    )
    base.update(overrides)
    return EvaluateAnswerRequest(**base)


def _stub(monkeypatch, facts=None):
    async def fake_eval(**kw):
        return facts or {
            "score": 75, "level": "GOOD", "matchedPoints": ["可见性"],
            "missingPoints": [], "riskPoints": [],
            "answerText": kw["answer_text"], "questionText": kw["question_text"],
        }
    monkeypatch.setattr(ae, "evaluate_answer", fake_eval)

    async def fake_llm(messages, settings, *, what="候选题", repair_error=None):
        user = next(m["content"] for m in messages if m["role"] == "user")
        topic = "Java 并发"
        for line in user.splitlines():
            if line.startswith("追问主题："):
                topic = line.split("：", 1)[1].strip() or topic
        return {"questionText": f"关于{topic}的追问。", "topic": topic,
                "sourceType": "KNOWLEDGE_BASE", "expectedPoints": ["深入点"],
                "targetPoint": topic}
    monkeypatch.setattr(qg, "call_deepseek_json", fake_llm)


def test_evaluate_returns_facts_and_followups(monkeypatch):
    _stub(monkeypatch)
    resp = asyncio.run(EvaluateAnswerGraph(_settings).evaluate(_request()))

    assert resp.success is True
    assert resp.score == 75
    assert resp.level == "GOOD"
    assert resp.matchedPoints == ["可见性"]
    # 得分 75 ≥ 70 且深度未达上限 → 生成 1 道深度追问
    assert 1 <= len(resp.followUpCandidates) <= 2
    assert all(c.candidateType == "FOLLOW_UP" for c in resp.followUpCandidates)
    assert all(c.topic == "Java 并发" for c in resp.followUpCandidates)


def test_weak_answer_returns_facts_without_followups(monkeypatch):
    _stub(monkeypatch, facts={"score": 15, "level": "WEAK", "matchedPoints": [],
                              "missingPoints": ["可见性"],
                              "riskPoints": [], "answerText": "不会", "questionText": "volatile 的作用？"})
    resp = asyncio.run(EvaluateAnswerGraph(_settings).evaluate(_request(answer_text="不会")))

    assert resp.success is True
    assert resp.score == 15
    # 得分 < 40：stage_controller 不产追问目标 → 空候选
    assert resp.followUpCandidates == []
