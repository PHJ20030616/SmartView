import asyncio

import pytest
from sqlalchemy import create_engine, text

from app.core.config import Settings
from app.core.errors import AppError
from app.services import profile_analyzer
from app.services.profile_analyzer import ProfileAnalyzer


def _settings() -> Settings:
    return Settings(_env_file=None)


def _profile_row() -> dict:
    return {
        "id": 12,
        "user_id": 7,
        "resume_file_id": 3,
        "version": 2,
        "confirm_status": "CONFIRMED",
        "deleted": 0,
        "candidate_name": "张三",
        "raw_text": "5 年 Java 后端经验",
        "project_experience_json": '[{"projectName":"电商平台"}]',
        "skills_json": '["Java", "Spring"]',
    }


def _analysis_payload() -> dict:
    return {
        "skillTags": [{"skill": "Java", "level": "EXPERT", "source": "PROJECT"}],
        "projectGraph": {"projects": []},
        "capabilityHints": {"engineering": ["a"], "architecture": [], "domain": []},
        "riskPoints": [{"category": "VAGUE_DESCRIPTION", "description": "描述空泛"}],
        "suggestedTopics": ["并发", "JVM"],
        "stageTargets": {"basic": ["b"], "project": ["p"], "scenario": ["s"]},
    }


def test_analyze_success_builds_profile_analysis(monkeypatch) -> None:
    analyzer = ProfileAnalyzer(_settings())
    monkeypatch.setattr(
        analyzer,
        "_load_confirmed_profile",
        lambda pid, ver: _profile_row(),
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_resume_context",
        lambda *a, **k: {"source": "mysql", "degraded": True, "chunks": [{"content": "简历片段"}]},
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_knowledge",
        lambda *a, **k: {"chunks": []},
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_experience",
        lambda *a, **k: {"chunks": []},
    )

    async def fake_llm(messages, settings, repair_error=None):
        assert any("skillTags" in m["content"] for m in messages if m["role"] == "system")
        return _analysis_payload()

    monkeypatch.setattr(profile_analyzer, "_call_deepseek_json", fake_llm)

    result = asyncio.run(analyzer.analyze("12", 2, "JAVA_BACKEND"))

    assert result.skillTags[0].skill == "Java"
    assert result.suggestedTopics == ["并发", "JVM"]
    assert result.modelName == "deepseek-v4-flash"
    assert result.modelVersion == "1.0.0"


def test_analyze_repairs_invalid_json_once(monkeypatch) -> None:
    analyzer = ProfileAnalyzer(_settings())
    monkeypatch.setattr(
        analyzer,
        "_load_confirmed_profile",
        lambda pid, ver: _profile_row(),
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_resume_context",
        lambda *a, **k: {"chunks": []},
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_knowledge",
        lambda *a, **k: {"chunks": []},
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_experience",
        lambda *a, **k: {"chunks": []},
    )

    calls: list[str | None] = []

    async def fake_llm(messages, settings, repair_error=None):
        calls.append(repair_error)
        if repair_error is None:
            raise AppError("模型返回空 JSON", code="LLM_INVALID_JSON", status_code=502)
        return _analysis_payload()

    monkeypatch.setattr(profile_analyzer, "_call_deepseek_json", fake_llm)

    result = asyncio.run(analyzer.analyze("12", 2, "JAVA_BACKEND"))

    assert result.skillTags[0].skill == "Java"
    # 第一次失败 + 一次修复调用
    assert len(calls) == 2
    assert calls[0] is None
    assert calls[1] is not None


def test_analyze_propagates_llm_request_failure(monkeypatch) -> None:
    analyzer = ProfileAnalyzer(_settings())
    monkeypatch.setattr(
        analyzer,
        "_load_confirmed_profile",
        lambda pid, ver: _profile_row(),
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_resume_context",
        lambda *a, **k: {"chunks": []},
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_knowledge",
        lambda *a, **k: {"chunks": []},
    )
    monkeypatch.setattr(
        profile_analyzer,
        "retrieve_experience",
        lambda *a, **k: {"chunks": []},
    )

    async def fake_llm(messages, settings, repair_error=None):
        raise AppError("LLM 服务暂时不可用", code="LLM_REQUEST_FAILED", status_code=502)

    monkeypatch.setattr(profile_analyzer, "_call_deepseek_json", fake_llm)

    with pytest.raises(AppError) as excinfo:
        asyncio.run(analyzer.analyze("12", 2, "JAVA_BACKEND"))

    assert excinfo.value.code == "LLM_REQUEST_FAILED"


def _sqlite_engine():
    """构造带 resume_profile 表的 SQLite 内存引擎，用于测试画像加载器。"""
    engine = create_engine("sqlite://")
    with engine.connect() as connection:
        connection.execute(
            text(
                """
                CREATE TABLE resume_profile (
                    id INTEGER PRIMARY KEY,
                    user_id INTEGER,
                    resume_file_id INTEGER,
                    version INTEGER,
                    confirm_status VARCHAR(20),
                    deleted INTEGER,
                    candidate_name VARCHAR(100),
                    raw_text TEXT,
                    project_experience_json TEXT,
                    skills_json TEXT
                )
                """
            )
        )
        connection.execute(
            text(
                """
                INSERT INTO resume_profile
                    (id, user_id, resume_file_id, version, confirm_status, deleted, candidate_name)
                VALUES (12, 7, 3, 2, 'CONFIRMED', 0, '张三')
                """
            )
        )
        connection.commit()
    return engine


def test_load_confirmed_profile_returns_owned_row() -> None:
    analyzer = ProfileAnalyzer(_settings(), engine=_sqlite_engine())
    row = analyzer._load_confirmed_profile(12, 2)
    assert int(row["user_id"]) == 7
    assert int(row["version"]) == 2


def test_load_confirmed_profile_rejects_unconfirmed() -> None:
    engine = _sqlite_engine()
    with engine.connect() as connection:
        connection.execute(
            text("UPDATE resume_profile SET confirm_status = 'UNCONFIRMED' WHERE id = 12")
        )
        connection.commit()

    analyzer = ProfileAnalyzer(_settings(), engine=engine)
    with pytest.raises(AppError) as excinfo:
        analyzer._load_confirmed_profile(12, 2)
    assert excinfo.value.code == "RESUME_PROFILE_NOT_CONFIRMED"


def test_load_confirmed_profile_rejects_stale_version() -> None:
    engine = _sqlite_engine()
    with engine.connect() as connection:
        connection.execute(
            text(
                """
                INSERT INTO resume_profile
                    (id, user_id, resume_file_id, version, confirm_status, deleted, candidate_name)
                VALUES (13, 7, 3, 3, 'CONFIRMED', 0, '张三')
                """
            )
        )
        connection.commit()

    analyzer = ProfileAnalyzer(_settings(), engine=engine)
    with pytest.raises(AppError) as excinfo:
        analyzer._load_confirmed_profile(12, 2)
    assert excinfo.value.code == "RESUME_PROFILE_VERSION_STALE"


def test_resolve_latest_confirmed_profile() -> None:
    engine = _sqlite_engine()
    with engine.connect() as connection:
        connection.execute(
            text(
                """
                INSERT INTO resume_profile
                    (id, user_id, resume_file_id, version, confirm_status, deleted, candidate_name)
                VALUES (13, 7, 3, 3, 'CONFIRMED', 0, '张三')
                """
            )
        )
        connection.commit()

    # 同一简历文件（resume_file_id=3）下有 version=3 的已确认画像，
    # 解析应返回 id=13、version=3，而不是传入画像自身（id=12, version=2）。
    profile_id, version = profile_analyzer.resolve_latest_confirmed_profile(
        "12", engine=engine
    )
    assert profile_id == 13
    assert version == 3
