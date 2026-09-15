"""回答评估图测试：评估事实 + 追问候选（替身 LLM/评估器）。

图内评估与追问生成并行，因此这里同时验证：两型候选齐备、弱答不产候选、
评估失败仍整单失败（保持"失败不落库、可重试"语义）。
"""
import asyncio
import time
from uuid import uuid4

from app.core.config import Settings
from app.core.errors import AppError
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


def _stub(monkeypatch, facts=None, prompt_sink=None):
    async def fake_eval(**kw):
        return facts or {
            "score": 75, "level": "GOOD", "matchedPoints": ["可见性"],
            "missingPoints": [], "riskPoints": [],
            "answerText": kw["answer_text"], "questionText": kw["question_text"],
        }
    monkeypatch.setattr(ae, "evaluate_answer", fake_eval)

    async def fake_llm(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        user = next(m["content"] for m in messages if m["role"] == "user")
        if prompt_sink is not None:
            prompt_sink.append(user)
        topic = "Java 并发"
        focus = ""
        for line in user.splitlines():
            if line.startswith("追问主题："):
                topic = line.split("：", 1)[1].strip() or topic
            if line.startswith("追问角度："):
                focus = line.split("：", 1)[1].strip()
        # 两型追问必须产出不同题面，否则会被候选池按 (topic, questionText) 去重
        return {"questionText": f"关于{topic}的追问：{focus}。", "topic": topic,
                "sourceType": "KNOWLEDGE_BASE", "expectedPoints": ["深入点"],
                "targetPoint": topic}
    monkeypatch.setattr(qg, "call_deepseek_json", fake_llm)


def test_evaluate_returns_facts_and_both_follow_up_kinds(monkeypatch):
    """并行产出：评估事实 + GAP/DEEP 两型追问候选（顺序固定）。"""
    _stub(monkeypatch)
    resp = asyncio.run(EvaluateAnswerGraph(_settings).evaluate(_request()))

    assert resp.success is True
    assert resp.score == 75
    assert resp.level == "GOOD"
    assert resp.matchedPoints == ["可见性"]
    assert [c.followUpKind for c in resp.followUpCandidates] == ["GAP", "DEEP"]
    assert all(c.candidateType == "FOLLOW_UP" for c in resp.followUpCandidates)
    assert all(c.topic == "Java 并发" for c in resp.followUpCandidates)


def test_follow_up_prompt_does_not_use_evaluation_facts(monkeypatch):
    """并行生成的追问提示词不得包含评估事实（得分/命中/缺失/风险）。"""
    prompts = []
    _stub(monkeypatch, prompt_sink=prompts)
    asyncio.run(EvaluateAnswerGraph(_settings).evaluate(_request()))

    assert len(prompts) == 2
    for prompt in prompts:
        assert "得分：" not in prompt
        assert "缺失要点：" not in prompt
        assert "风险点：" not in prompt
        assert "volatile 保证可见性与禁止重排" in prompt


def test_weak_answer_returns_facts_without_followups(monkeypatch):
    """空白/明确不会的回答不消耗 LLM 生成追问（确定性判定，与评估侧同一套规则）。"""
    _stub(monkeypatch, facts={"score": 15, "level": "WEAK", "matchedPoints": [],
                              "missingPoints": ["可见性"],
                              "riskPoints": [], "answerText": "不会", "questionText": "volatile 的作用？"})
    resp = asyncio.run(EvaluateAnswerGraph(_settings).evaluate(_request(answer_text="不会")))

    assert resp.success is True
    assert resp.score == 15
    assert resp.followUpCandidates == []


def test_generation_starts_before_evaluation_finishes(monkeypatch):
    """并行回归守卫：生成分支必须在评估结束前就开始，且慢评估不会丢掉候选。

    LangGraph 的 Pregel 执行模型是"超步内并发、超步之间设栅栏"：首版实现把生成分支写成
    两跳（stage_controller → generate_questions），它落到下一个超步，必须等评估跑完才启动，
    实测退化为串行（接口 28.3s）。本用例用"慢评估 + 记录生成开始时刻"把这个形态锁住，
    仅断言"调用了两次生成"是不够的——串行实现同样满足该断言。
    """
    events: list[tuple[str, float]] = []

    async def slow_eval(**kw):
        events.append(("eval_start", time.perf_counter()))
        await asyncio.sleep(0.3)
        events.append(("eval_end", time.perf_counter()))
        return {"score": 75, "level": "GOOD", "matchedPoints": [], "missingPoints": [],
                "riskPoints": [], "answerText": kw["answer_text"], "questionText": kw["question_text"]}

    async def fake_llm(messages, settings, *, what="候选题", repair_error=None, **_kwargs):
        events.append(("gen", time.perf_counter()))
        focus = ""
        for line in next(m["content"] for m in messages if m["role"] == "user").splitlines():
            if line.startswith("追问角度："):
                focus = line.split("：", 1)[1].strip()
        return {"questionText": f"关于并发的追问：{focus}。", "topic": "Java 并发",
                "sourceType": "KNOWLEDGE_BASE", "expectedPoints": ["深入点"], "targetPoint": "Java 并发"}

    monkeypatch.setattr(ae, "evaluate_answer", slow_eval)
    monkeypatch.setattr(qg, "call_deepseek_json", fake_llm)

    resp = asyncio.run(EvaluateAnswerGraph(_settings).evaluate(_request()))

    assert resp.success is True
    eval_end = [ts for name, ts in events if name == "eval_end"][0]
    gen_starts = [ts for name, ts in events if name == "gen"]
    assert len(gen_starts) == 2
    assert min(gen_starts) < eval_end, "生成分支应在评估结束前启动（否则说明两分支退化成了串行）"
    assert [c.followUpKind for c in resp.followUpCandidates] == ["GAP", "DEEP"]


def test_evaluation_failure_keeps_whole_request_failed(monkeypatch):
    """并行不改变失败语义：评估分支异常 → success=false（调用方失败不落库、可重试）。"""
    async def failing_eval(**kw):
        raise AppError("回答评估失败：模型不可用", code="LLM_REQUEST_FAILED")

    monkeypatch.setattr(ae, "evaluate_answer", failing_eval)
    resp = asyncio.run(EvaluateAnswerGraph(_settings).evaluate(_request()))

    assert resp.success is False
    assert resp.errorMessage
