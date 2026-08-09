"""回答评估服务测试：弱回答关键词直判 + LLM 正常评分。"""
import asyncio

from app.core.config import Settings
from app.services import answer_evaluator as ae

_settings = Settings(_env_file=None)


def _run(**kw):
    return asyncio.run(ae.evaluate_answer(
        question_text=kw.get("question_text", "volatile 的作用？"),
        answer_text=kw.get("answer_text", ""),
        expected_points=kw.get("expected_points", ["可见性"]),
        role_direction=kw.get("role_direction", "JAVA_BACKEND"),
        settings=_settings,
    ))


def test_weak_keyword_returns_low_score_without_llm():
    facts = _run(answer_text="这个我不会，不太熟悉")
    assert facts["score"] < 40
    assert facts["level"] == "WEAK"
    assert facts["matchedPoints"] == []
    # 弱回答也带回题目/回答文本，供后续追问生成
    assert facts["questionText"] == "volatile 的作用？"


def test_short_answer_considered_weak():
    # "不会"命中否定关键词 → 直接低分
    facts = _run(answer_text="不会")
    assert facts["score"] < 40


def test_short_confident_answer_not_weak(monkeypatch):
    # 简短但肯定的回答（如"了解"）不应被误判为弱答，应走 LLM 评估
    async def fake(messages, settings, *, what="回答评估", repair_error=None):
        return {"score": 80, "level": "GOOD", "matchedPoints": ["了解"],
                "missingPoints": [], "riskPoints": []}
    monkeypatch.setattr(ae, "call_deepseek_json", fake)

    facts = _run(answer_text="了解")
    assert facts["score"] == 80
    assert facts["level"] == "GOOD"


def test_normal_answer_calls_llm_and_normalizes(monkeypatch):
    async def fake(messages, settings, *, what="回答评估", repair_error=None):
        assert settings is _settings
        assert what == "回答评估"
        return {"score": 85, "level": "GOOD", "matchedPoints": ["可见性"],
                "missingPoints": [], "riskPoints": []}
    monkeypatch.setattr(ae, "call_deepseek_json", fake)

    facts = _run(answer_text="volatile 保证可见性与禁止重排序")
    assert facts["score"] == 85
    assert facts["matchedPoints"] == ["可见性"]
    assert facts["answerText"] == "volatile 保证可见性与禁止重排序"
