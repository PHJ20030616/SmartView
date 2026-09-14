"""报告生成服务测试：确定性评分公式、答案类型映射、生成编排。"""

import asyncio
import json

import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.schemas.report import ReportGenerateResult
from app.services import report_generator
from app.services.report_generator import (
    ANSWER_TYPE_BY_STAGE,
    ReferenceAnswerGenerator,
    ReportGenerator,
    ReportNarrativeGenerator,
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

    async def fake_call(messages, settings, *, what="结果", repair_error=None, **_kwargs):
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


def test_reference_answers_missing_question_raises_validation_error() -> None:
    """单题场景 LLM 没返回本题 → _validate_one 抛 ValueError（触发修复调用）。

    逐题生成后，"覆盖全部已答题"由调用结构保证（每题各发一次请求），
    因此这里的失败形态变成"返回的 questionId 不是本次请求的题目"。
    """
    question = {"question_id": "1", "question_text": "Q1", "stage": "BASIC"}
    stage_by = {"1": "BASIC", "2": "PROJECT", "3": "SCENARIO"}
    raw = {
        "referenceAnswers": [
            {"questionId": "2", "referenceContent": "内容2", "keyPoints": [], "tradeoffs": []},
        ]
    }
    with pytest.raises(ValueError, match="缺少本次请求的题目"):
        ReferenceAnswerGenerator()._validate_one(raw, question, stage_by)


def test_reference_answers_missing_question_raises_app_error(monkeypatch) -> None:
    """修复调用后仍拿不到本题参考答案 → 抛 AppError 终态码（worker 不再重试）。"""
    questions = [
        {"question_id": "1", "question_text": "Q1", "stage": "BASIC"},
        {"question_id": "2", "question_text": "Q2", "stage": "PROJECT"},
        {"question_id": "3", "question_text": "Q3", "stage": "SCENARIO"},
    ]
    stage_by = {"1": "BASIC", "2": "PROJECT", "3": "SCENARIO"}
    # 两次调用均返回缺字段的 payload：首次 _validate_one 失败触发修复调用，
    # 修复后仍失败 → generate 抛 AppError（确定性终态）。
    incomplete = {"referenceAnswers": [{"questionId": "1", "referenceContent": ""}]}
    calls: list[str | None] = []

    async def fake_call(messages, settings, *, what="参考答案", repair_error=None, **_kwargs):
        calls.append(repair_error)
        return incomplete

    monkeypatch.setattr(report_generator, "call_deepseek_json", fake_call)
    with pytest.raises(AppError) as excinfo:
        asyncio.run(ReferenceAnswerGenerator().generate(questions, stage_by))
    # 3 道题各自调用一次，且每个失败点都补了一次修复调用
    assert len(calls) == 6
    assert calls.count(None) == 3
    assert excinfo.value.code == "REPORT_REFERENCE_VALIDATION_FAILED"
    # 错误信息必须能定位到具体题目：逐题生成后这是排查的唯一抓手
    assert "题目" in excinfo.value.message


def test_reference_answers_invalid_json_triggers_repair_call(monkeypatch) -> None:
    """模型首次返回非法 JSON（长输出被截断）时也要修复一次，而不是直接进终态。

    修复重试此前只覆盖"JSON 合法但字段不满足 schema"；JSON 解析失败会立刻抛
    LLM_INVALID_JSON，多题会话一旦被截断就再没有第二次机会。
    输出被截断现在有独立错误码 LLM_OUTPUT_TRUNCATED，同样必须进修复路径。
    """
    questions = [{"question_id": "1", "question_text": "Q1", "stage": "BASIC"}]
    stage_by = {"1": "BASIC"}
    calls: list[dict] = []

    async def fake_call(messages, settings, *, what="参考答案", repair_error=None, **kwargs):
        calls.append({"what": what, "repair_error": repair_error, "max_tokens": kwargs.get("max_tokens")})
        if repair_error is None:
            raise AppError(
                "模型输出的参考答案达到输出上限被截断，未能形成完整 JSON",
                code="LLM_OUTPUT_TRUNCATED",
                status_code=502,
            )
        return {
            "referenceAnswers": [
                {"questionId": "1", "referenceContent": "内容1", "keyPoints": ["k"], "tradeoffs": []}
            ]
        }

    monkeypatch.setattr(report_generator, "call_deepseek_json", fake_call)
    items = asyncio.run(ReferenceAnswerGenerator().generate(questions, stage_by))

    assert len(calls) == 2
    assert calls[0]["repair_error"] is None
    # 修复调用必须带上首次错误原文，模型才知道要改什么
    assert "被截断" in calls[1]["repair_error"]
    assert items[0]["referenceContent"] == "内容1"
    # 两次调用都使用单题上限：逐题生成后单次输出与题量解耦，
    # 不再需要为"一次生成全部题"放宽到 16384
    assert {call["max_tokens"] for call in calls} == {
        report_generator._REFERENCE_ANSWER_MAX_TOKENS_PER_QUESTION
    }
    assert report_generator._REFERENCE_ANSWER_MAX_TOKENS_PER_QUESTION < 16384


def test_reference_answers_transport_failure_is_not_repaired(monkeypatch) -> None:
    """请求级失败（网络/上游不可用）重发同一份提示词不会改变结果，不做修复调用。"""
    questions = [{"question_id": "1", "question_text": "Q1", "stage": "BASIC"}]
    calls: list[str | None] = []

    async def fake_call(messages, settings, *, what="参考答案", repair_error=None, **_kwargs):
        calls.append(repair_error)
        raise AppError("AI 生成服务暂时不可用，请稍后重试", code="LLM_REQUEST_FAILED", status_code=502)

    monkeypatch.setattr(report_generator, "call_deepseek_json", fake_call)
    with pytest.raises(AppError) as excinfo:
        asyncio.run(ReferenceAnswerGenerator().generate(questions, {"1": "BASIC"}))

    assert len(calls) == 1
    assert excinfo.value.code == "LLM_REQUEST_FAILED"


def test_reference_answers_rejects_foreign_and_empty_question_ids() -> None:
    """返回的 questionId 不是本次请求的题目、或缺少正文，一律抛 ValueError。

    逐题生成后，每次调用只请求一道题：模型返回别题（并发场景下会覆盖别人的数据）
    或缺 referenceContent（前端展示为空）都必须判为无效，交由修复调用重试。
    """
    stage_by = {"1": "BASIC", "2": "PROJECT"}
    question = {"question_id": "1", "question_text": "Q1", "stage": "BASIC"}

    # 返回了本会话之外 / 另一道题的 questionId
    with pytest.raises(ValueError, match="缺少本次请求的题目"):
        ReferenceAnswerGenerator()._validate_one(
            {
                "referenceAnswers": [
                    {"questionId": "99", "referenceContent": "越权内容", "keyPoints": [], "tradeoffs": []},
                ]
            },
            question,
            stage_by,
        )

    # 命中本题但缺正文
    with pytest.raises(ValueError, match="缺少 referenceContent"):
        ReferenceAnswerGenerator()._validate_one(
            {"referenceAnswers": [{"questionId": "1", "referenceContent": "   "}]},
            question,
            stage_by,
        )


def test_reference_answers_are_generated_per_question_with_bounded_concurrency(monkeypatch) -> None:
    """每道题各发一次请求（输出量与题量解耦），且并发度受配置约束。

    这是"输出线性增长撞 max_tokens"这一结构性问题的修复断言：
    3 道题必须产生 3 次调用，而不是历史上的 1 次批量调用。
    """
    questions = [
        {"question_id": str(index), "question_text": f"Q{index}", "stage": "BASIC"}
        for index in (1, 2, 3)
    ]
    stage_by = {str(index): "BASIC" for index in (1, 2, 3)}
    seen_question_ids: list[str] = []
    # 记录同时在飞的调用数，验证信号量确实生效（配置为 1 时不允许并发）
    in_flight = 0
    max_in_flight = 0

    async def fake_call(messages, settings, *, what="参考答案", **_kwargs):
        nonlocal in_flight, max_in_flight
        in_flight += 1
        max_in_flight = max(max_in_flight, in_flight)
        # 从提示词里反查本次请求的是哪一道题（提示词是 json.dumps(questions) 形式）
        for question in questions:
            if question["question_text"] in messages[1]["content"]:
                seen_question_ids.append(question["question_id"])
        await asyncio.sleep(0)
        in_flight -= 1
        return {
            "referenceAnswers": [
                {
                    "questionId": question["question_id"],
                    "referenceContent": f"内容{question['question_id']}",
                    "keyPoints": [],
                    "tradeoffs": [],
                }
                for question in questions
                if question["question_text"] in messages[1]["content"]
            ]
        }

    monkeypatch.setattr(report_generator, "call_deepseek_json", fake_call)
    settings = Settings(_env_file=None, deepseek_api_key="sk-test", llm_max_concurrency=1)
    items = asyncio.run(ReferenceAnswerGenerator(settings).generate(questions, stage_by))

    assert sorted(seen_question_ids) == ["1", "2", "3"]
    assert max_in_flight == 1
    # 返回顺序与入参一致，便于逐题落库与前端展示
    assert [item["questionId"] for item in items] == ["1", "2", "3"]


# ==================== ReportNarrativeGenerator ====================

def test_narrative_second_validation_failure_raises_app_error(monkeypatch) -> None:
    """报告评语首次校验失败、修复调用后仍缺字段 → 抛 AppError 终态码。

    monkeypatch 替换的是模块级函数 call_deepseek_json（非类方法，fake 无需 self），
    签名与真实函数一致以承接第二次调用携带的 repair_error 关键字参数。
    """
    incomplete = {"summary": "总体评价"}  # 缺 strengths/weaknesses/riskPoints/suggestions
    calls: list[str | None] = []

    async def fake_call(messages, settings, *, what="报告评语", repair_error=None, **_kwargs):
        # 两次调用均返回缺字段 payload：首次 _validate 失败触发带修复上下文的二次调用，
        # 修复后仍校验失败 → generate 抛 AppError（确定性终态，worker 不再重试）。
        calls.append(repair_error)
        return incomplete

    monkeypatch.setattr(report_generator, "call_deepseek_json", fake_call)
    with pytest.raises(AppError) as excinfo:
        asyncio.run(ReportNarrativeGenerator().generate({}))
    # 首次调用无修复上下文、修复调用携带首次校验错误 → 证明修复路径确实被走到
    assert len(calls) == 2
    assert calls[0] is None
    assert calls[1] is not None
    assert excinfo.value.code == "REPORT_LLM_VALIDATION_FAILED"


def test_narrative_invalid_json_triggers_repair_call(monkeypatch) -> None:
    """报告评语的"JSON 解析失败"也要修复一次，与参考答案路径保持一致。"""
    complete = {
        "summary": "总体评价",
        "strengths": ["优势"],
        "weaknesses": ["薄弱"],
        "riskPoints": ["风险"],
        "suggestions": [{"topic": "主题", "reason": "原因", "resources": ["资料"]}],
    }
    calls: list[str | None] = []

    async def fake_call(messages, settings, *, what="报告评语", repair_error=None, **_kwargs):
        calls.append(repair_error)
        if repair_error is None:
            raise AppError(
                "模型返回的报告评语 JSON 格式无效",
                code="LLM_INVALID_JSON",
                status_code=502,
            )
        return complete

    monkeypatch.setattr(report_generator, "call_deepseek_json", fake_call)
    result = asyncio.run(ReportNarrativeGenerator().generate({}))

    assert len(calls) == 2
    assert calls[0] is None
    assert calls[1] == "模型返回的报告评语 JSON 格式无效"
    assert result["summary"] == "总体评价"


# ==================== ReportGenerator ====================

def test_generate_missing_report_raises_app_error(monkeypatch) -> None:
    """会话存在但报告尚未创建时，应抛出 REPORT_NOT_FOUND。

    必须同时打桩 _load_session：generate() 会先加载会话，若让它走真实数据库，
    本用例在本地是"会话不存在也抛 AppError"的假绿灯（看起来通过，其实没走到
    报告缺失这条分支），在无 MySQL 的 CI 上则直接变成连接错误。
    """
    gen = ReportGenerator()
    monkeypatch.setattr(gen, "_load_session",
                        lambda sid: {"resume_profile_id": 12, "profile_analysis_id": 3})

    def fake_load_report_id(session_id):
        raise AppError("面试报告尚未创建", code="REPORT_NOT_FOUND")

    monkeypatch.setattr(gen, "_load_report_id", fake_load_report_id)
    with pytest.raises(AppError) as excinfo:
        asyncio.run(gen.generate("88"))
    assert excinfo.value.code == "REPORT_NOT_FOUND"


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
