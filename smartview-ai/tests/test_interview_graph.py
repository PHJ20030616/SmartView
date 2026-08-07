"""面试首题生成 LangGraph 状态机测试。

覆盖：成功生成首题并组装契约响应、BASIC 必覆盖主题提取与回退、
来源类型归一、画像前置校验失败、检索为空降级、引用溯源构建。
"""

import asyncio
from uuid import uuid4

import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.graphs import interview_graph
from app.graphs.interview_graph import FirstQuestionGraph
from app.schemas.interview import (
    GenerateFirstQuestionRequest,
    StagePlan,
    StagePlanStage,
)


def _settings() -> Settings:
    return Settings(_env_file=None)


def _request(**overrides) -> GenerateFirstQuestionRequest:
    """构造合法的首题生成请求，可局部覆盖字段。"""
    base = dict(
        sessionId="1",
        roleDirection="JAVA_BACKEND",
        stagePlan=StagePlan(
            policy_version="1.0",
            total_min_questions=7,
            total_max_questions=20,
            stages=[
                StagePlanStage(
                    stage="BASIC",
                    min_questions=3,
                    max_questions=8,
                    required_topics=["Java 并发", "JVM"],
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
        ),
        resumeProfileId="10",
        profileVersion=1,
        traceId=uuid4(),
    )
    base.update(overrides)
    return GenerateFirstQuestionRequest(**base)


def _profile_row() -> dict:
    return {
        "id": 10,
        "user_id": 7,
        "resume_file_id": 3,
        "version": 1,
        "confirm_status": "CONFIRMED",
        "deleted": 0,
        "candidate_name": "张三",
        "raw_text": "5 年 Java 后端经验",
        "project_experience_json": '[{"projectName":"电商平台"}]',
        "skills_json": '["Java", "Spring"]',
    }


def _llm_payload(**overrides) -> dict:
    """模拟 DeepSeek 返回的首题 JSON。"""
    base = {
        "questionText": "请解释 Java 内存模型中的 happens-before 原则。",
        "topic": "Java 并发",
        "sourceType": "MIXED",
        "expectedPoints": ["能说出定义", "能举例说明"],
    }
    base.update(overrides)
    return base


def _patch_dependencies(monkeypatch, *, profile=None, llm=None, retrievers=None) -> FirstQuestionGraph:
    """统一打桩外部依赖并返回图实例。"""
    graph = FirstQuestionGraph(_settings())
    effective_profile = _profile_row() if profile is None else profile
    monkeypatch.setattr(
        graph, "_load_confirmed_profile", lambda pid, ver: effective_profile
    )

    if llm is not None:
        async def fake_llm(messages, settings, *, what="首题", repair_error=None):
            # llm 可以是固定 dict（直接返回）或 callable（动态生成/捕获断言）
            if callable(llm):
                return await llm(messages, settings, what=what, repair_error=repair_error)
            return llm

        monkeypatch.setattr(interview_graph, "call_deepseek_json", fake_llm)

    default_ctx = {"chunks": [{"content": "知识片段", "metadata": {"title": "并发", "category": "并发"}}]}
    ctx_map = retrievers or {
        "knowledge": default_ctx,
        "experience": {"chunks": [{"content": "面经片段", "metadata": {"title": "面经", "scenario": "并发"}}]},
        "resume": {"chunks": []},
    }
    monkeypatch.setattr(
        interview_graph, "retrieve_knowledge", lambda *a, **k: ctx_map["knowledge"]
    )
    monkeypatch.setattr(
        interview_graph, "retrieve_experience", lambda *a, **k: ctx_map["experience"]
    )
    monkeypatch.setattr(
        interview_graph, "retrieve_resume_context", lambda *a, **k: ctx_map["resume"]
    )
    return graph


def test_generate_success_assembles_question(monkeypatch) -> None:
    graph = _patch_dependencies(monkeypatch, llm=_llm_payload())

    result = asyncio.run(graph.generate(_request()))

    assert result.success is True
    assert result.questionText == "请解释 Java 内存模型中的 happens-before 原则。"
    assert result.topic == "Java 并发"
    assert result.questionType == "OPENING"
    assert result.sourceType == "MIXED"
    assert result.expectedPoints == ["能说出定义", "能举例说明"]
    # 引用来自真实检索结果（溯源），且能回填标题
    assert len(result.knowledgeRefs) == 1
    assert result.knowledgeRefs[0].title == "并发"
    assert len(result.caseRefs) == 1
    assert result.caseRefs[0].title == "面经"


def test_basic_topics_extracted_from_stage_plan(monkeypatch) -> None:
    captured = {}

    async def fake_llm(messages, settings, *, what="首题", repair_error=None):
        user = next(m["content"] for m in messages if m["role"] == "user")
        captured["user"] = user
        return _llm_payload()

    graph = _patch_dependencies(monkeypatch, llm=fake_llm)

    asyncio.run(graph.generate(_request()))

    # LLM 提示词应包含阶段计划的 BASIC 必覆盖主题
    assert "Java 并发" in captured["user"]
    assert "JVM" in captured["user"]


def test_missing_basic_topics_falls_back_to_default(monkeypatch) -> None:
    captured = {}

    async def fake_llm(messages, settings, *, what="首题", repair_error=None):
        user = next(m["content"] for m in messages if m["role"] == "user")
        captured["user"] = user
        return _llm_payload()

    plan = StagePlan(stages=[])  # 无 BASIC 阶段
    graph = _patch_dependencies(monkeypatch, llm=fake_llm)

    asyncio.run(graph.generate(_request(stagePlan=plan)))

    # 回退到方向默认主题
    assert "Java 并发" in captured["user"]
    assert "JVM" in captured["user"]


def test_unconfirmed_profile_returns_business_failure(monkeypatch) -> None:
    graph = _patch_dependencies(monkeypatch)

    def raise_unconfirmed(pid, ver):
        raise AppError("简历画像尚未确认，无法生成首题", code="RESUME_PROFILE_NOT_CONFIRMED")

    monkeypatch.setattr(graph, "_load_confirmed_profile", raise_unconfirmed)

    result = asyncio.run(graph.generate(_request()))

    assert result.success is False
    assert "尚未确认" in result.errorMessage


def test_stale_profile_version_returns_business_failure(monkeypatch) -> None:
    graph = _patch_dependencies(monkeypatch)

    def raise_stale(pid, ver):
        raise AppError("简历画像版本已更新，当前请求已失效", code="RESUME_PROFILE_VERSION_STALE")

    monkeypatch.setattr(graph, "_load_confirmed_profile", raise_stale)

    result = asyncio.run(graph.generate(_request()))

    assert result.success is False
    assert "版本已更新" in result.errorMessage


def test_invalid_source_type_normalized_to_knowledge_base(monkeypatch) -> None:
    graph = _patch_dependencies(
        monkeypatch, llm=_llm_payload(sourceType="UNKNOWN")
    )

    result = asyncio.run(graph.generate(_request()))

    assert result.success is True
    assert result.sourceType == "KNOWLEDGE_BASE"


def test_empty_retrieval_still_generates_question(monkeypatch) -> None:
    graph = _patch_dependencies(
        monkeypatch,
        llm=_llm_payload(sourceType="KNOWLEDGE_BASE"),
        retrievers={
            "knowledge": {"chunks": []},
            "experience": {"chunks": []},
            "resume": {"chunks": []},
        },
    )

    result = asyncio.run(graph.generate(_request()))

    assert result.success is True
    # 无检索材料时引用为空，但仍能基于简历与计划出题
    assert result.knowledgeRefs == []
    assert result.caseRefs == []


def test_llm_error_returns_failure_response(monkeypatch) -> None:
    async def failing_llm(messages, settings, *, what="首题", repair_error=None):
        raise AppError("AI 生成服务暂时不可用", code="LLM_REQUEST_FAILED")

    graph = _patch_dependencies(monkeypatch, llm=failing_llm)

    result = asyncio.run(graph.generate(_request()))

    assert result.success is False
    assert "暂时不可用" in result.errorMessage
