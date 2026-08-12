"""报告生成服务测试：确定性评分公式、答案类型映射、生成编排。"""

import asyncio
import json

import pytest

from app.core.errors import AppError
from app.schemas.report import ReportGenerateResult
from app.services import report_generator
from app.services.report_generator import (
    ANSWER_TYPE_BY_STAGE,
    ReferenceAnswerGenerator,
    ReportGenerator,
    ReportScorer,
)


# ==================== ReportScorer ====================

def test_overall_score_is_weighted_mean() -> None:
    scorer = ReportScorer(
        evaluations=[
            {"question_id": "1", "order": 1, "stage": "BASIC", "score": 60},
            {"question_id": "2", "order": 2, "stage": "BASIC", "score": 80},
        ],
        stage_plan={},
        stage_coverage={},
    )
    # 权重 w=1+0.2×order → (60×1.2 + 80×1.4) / (1.2+1.4) = (72+112)/2.6 = 70.77 → 71
    assert scorer.overall_score() == 71


def test_readiness_level_thresholds() -> None:
    for score, expected in [
        (30, "NOT_READY"),
        (50, "NEEDS_PRACTICE"),
        (70, "READY"),
        (90, "WELL_PREPARED"),
    ]:
        scorer = ReportScorer(
            evaluations=[{"question_id": "1", "order": 1, "stage": "BASIC", "score": score}],
            stage_plan={},
            stage_coverage={},
        )
        assert scorer.readiness_level() == expected


def test_role_fit_score_uses_project_scenario_only() -> None:
    scorer = ReportScorer(
        evaluations=[
            {"question_id": "1", "order": 1, "stage": "BASIC", "score": 40},
            {"question_id": "2", "order": 2, "stage": "PROJECT", "score": 90},
            {"question_id": "3", "order": 3, "stage": "SCENARIO", "score": 80},
        ],
        stage_plan={},
        stage_coverage={},
    )
    # role 仅 PROJECT/SCENARIO：(90×1.4 + 80×1.6) / (1.4+1.6) = (126+128)/3.0 = 84.67 → 85
    assert scorer.role_fit_score() == 85


def test_role_fit_falls_back_to_overall_when_no_role_questions() -> None:
    scorer = ReportScorer(
        evaluations=[{"question_id": "1", "order": 1, "stage": "BASIC", "score": 70}],
        stage_plan={},
        stage_coverage={},
    )
    assert scorer.role_fit_score() == 70


def test_coverage_computes_ratio_per_stage() -> None:
    scorer = ReportScorer(
        evaluations=[],
        stage_plan={
            "stages": [
                {"stage": "BASIC", "required_topics": ["并发", "JVM", "Spring"]},
                {"stage": "PROJECT", "required_topics": []},
            ]
        },
        stage_coverage={
            "BASIC": {"covered_topics": ["并发", "JVM"]},
            "PROJECT": {"covered_topics": ["项目A"]},
        },
    )
    assert scorer.coverage()["basicCoverage"] == round(2 / 3, 2)
    assert scorer.coverage()["projectCoverage"] == 1.0  # 无必覆盖主题视为全覆盖


# ==================== ReferenceAnswerGenerator ====================

def test_answer_type_mapped_by_stage_deterministically(monkeypatch) -> None:
    questions = [
        {"question_id": "1", "question_text": "Q1", "stage": "BASIC"},
        {"question_id": "2", "question_text": "Q2", "stage": "PROJECT"},
        {"question_id": "3", "question_text": "Q3", "stage": "SCENARIO"},
    ]
    stage_by = {"1": "BASIC", "2": "PROJECT", "3": "SCENARIO"}

    async def fake_call(messages, settings, *, what="结果", repair_error=None):
        return {
            "referenceAnswers": [
                {"questionId": "1", "referenceContent": "内容1", "keyPoints": ["k"], "tradeoffs": []},
                {"questionId": "2", "referenceContent": "内容2", "keyPoints": ["k"], "tradeoffs": []},
                {"questionId": "3", "referenceContent": "内容3", "keyPoints": ["k"],
                 "tradeoffs": [{"aspect": "一致性", "options": ["AP", "CP"]}]},
            ]
        }

    monkeypatch.setattr(report_generator, "call_deepseek_json", fake_call)
    items = asyncio.run(
        ReferenceAnswerGenerator().generate(questions, stage_by)
    )
    assert [i["answerType"] for i in items] == [
        "BASIC_KEY_POINTS", "PROJECT_STRUCTURE", "SCENARIO_FRAMEWORK"
    ]
    assert ANSWER_TYPE_BY_STAGE["BASIC"] == "BASIC_KEY_POINTS"


# ==================== ReportGenerator ====================

def test_generate_missing_report_raises_app_error(monkeypatch) -> None:
    gen = ReportGenerator()

    def fake_load_report_id(session_id):
        raise AppError("面试报告尚未创建", code="REPORT_NOT_FOUND")

    monkeypatch.setattr(gen, "_load_report_id", fake_load_report_id)
    with pytest.raises(AppError):
        asyncio.run(gen.generate("88"))


def test_generate_no_answered_question_raises_app_error(monkeypatch) -> None:
    gen = ReportGenerator()
    monkeypatch.setattr(gen, "_load_session",
                        lambda sid: {"resume_profile_id": 12, "profile_analysis_id": 3})
    monkeypatch.setattr(gen, "_load_report_id", lambda sid: 5)
    monkeypatch.setattr(gen, "_load_answered_questions", lambda sid: [])
    with pytest.raises(AppError) as excinfo:
        asyncio.run(gen.generate("88"))
    assert excinfo.value.code == "NO_ANSWERED_QUESTION"


def test_generate_returns_full_report(monkeypatch) -> None:
    gen = ReportGenerator()
    monkeypatch.setattr(gen, "_load_session", lambda sid: {
        "resume_profile_id": 12,
        "profile_analysis_id": 3,
        "role_direction": "JAVA_BACKEND",
        "stage_plan_json": json.dumps({"stages": [
            {"stage": "BASIC", "required_topics": ["并发"]},
        ]}),
        "stage_coverage_json": json.dumps({"BASIC": {"covered_topics": ["并发"]}}),
    })
    monkeypatch.setattr(gen, "_load_report_id", lambda sid: 5)
    monkeypatch.setattr(gen, "_load_answered_questions", lambda sid: [
        {"id": 10, "question_order": 1, "stage": "BASIC", "topic": "并发",
         "question_text": "并发与并行的区别", "source_type": "KNOWLEDGE_BASE",
         "expected_points_json": '["要点"]'},
    ])
    monkeypatch.setattr(gen, "_load_answers", lambda sid: [
        {"question_id": 10, "answer_text": "我的回答"},
    ])
    monkeypatch.setattr(gen, "_load_evaluations", lambda sid: [
        {"question_id": 10, "score": 80, "level": "GOOD",
         "matched_points_json": '["要点"]', "missing_points_json": "[]",
         "risk_points_json": "[]"},
    ])
    monkeypatch.setattr(gen, "_load_profile", lambda pid: {"candidateName": "张三", "skills": []})
    monkeypatch.setattr(gen, "_load_analysis", lambda aid: {})

    # 注意：monkeypatch 替换的是类方法，fake 必须接收 self（实例作为第一个位置参数）。
    async def fake_narrative(self, context):
        return {"summary": "总体评价", "strengths": ["基础扎实"],
                "weaknesses": ["深度不足"], "riskPoints": ["风险"],
                "suggestions": [{"topic": "并发", "reason": "薄弱", "resources": []}]}

    async def fake_ref(self, questions, stage_by):
        return [{"questionId": "10", "answerType": "BASIC_KEY_POINTS",
                 "referenceContent": "参考答案", "keyPoints": ["要点"], "tradeoffs": []}]

    monkeypatch.setattr(report_generator.ReportNarrativeGenerator, "generate", fake_narrative)
    monkeypatch.setattr(report_generator.ReferenceAnswerGenerator, "generate", fake_ref)

    result = asyncio.run(gen.generate("88"))
    assert isinstance(result, ReportGenerateResult)
    assert result.reportId == "5"
    assert result.overallScore == 80
    # 阈值边界：score=80 满足 score>=80 → WELL_PREPARED（阈值见 test_readiness_level_thresholds）
    assert result.readinessLevel == "WELL_PREPARED"
    assert result.referenceAnswers[0].questionId == "10"
    assert result.coverage.basicCoverage == 1.0
