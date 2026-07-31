from __future__ import annotations

from contextlib import nullcontext
from unittest.mock import Mock

from app.retrievers.resume_retriever import ResumeRetriever


def test_retrieve_builds_server_side_tenant_and_version_filter() -> None:
    connection = Mock()
    connection.execute.return_value.mappings.return_value.first.return_value = {
        "id": 12,
        "user_id": 7,
        "version": 3,
        "confirm_status": "CONFIRMED",
        "candidate_name": "张三",
        "raw_text": "简历原文",
        "project_experience_json": "[]",
        "skills_json": "[]",
    }
    engine = Mock()
    engine.connect.return_value = nullcontext(connection)

    collection = Mock()
    collection.query.return_value = {
        "documents": [["项目经历：推荐系统"]],
        "metadatas": [[
            {
                "user_id": 7,
                "resume_profile_id": 12,
                "profile_version": 3,
            }
        ]],
        "distances": [[0.12]],
    }

    result = ResumeRetriever(engine=engine, collection=collection).retrieve(
        "推荐系统",
        user_id=7,
        resume_profile_id=12,
        top_k=3,
    )

    assert result["source"] == "chroma"
    assert result["degraded"] is False
    assert result["chunks"][0]["content"] == "项目经历：推荐系统"
    collection.query.assert_called_once_with(
        query_texts=["推荐系统"],
        n_results=3,
        where={
            "$and": [
                {"user_id": 7},
                {"resume_profile_id": 12},
                {"profile_version": 3},
            ]
        },
    )


def test_retrieve_degrades_to_mysql_when_chroma_is_unavailable() -> None:
    connection = Mock()
    connection.execute.return_value.mappings.return_value.first.return_value = {
        "id": 12,
        "user_id": 7,
        "version": 3,
        "confirm_status": "CONFIRMED",
        "candidate_name": "张三",
        "raw_text": "简历原文",
        "project_experience_json": '[{"name":"项目A"}]',
        "skills_json": '["Python"]',
    }
    engine = Mock()
    engine.connect.return_value = nullcontext(connection)

    collection = Mock()
    collection.query.side_effect = RuntimeError("Chroma 暂时不可用")

    result = ResumeRetriever(engine=engine, collection=collection).retrieve(
        "Python",
        user_id=7,
        resume_profile_id=12,
    )

    assert result["source"] == "mysql"
    assert result["degraded"] is True
    assert "简历原文" in result["chunks"][0]["content"]
    assert "项目经历" in result["chunks"][0]["content"]
    assert "技能描述" in result["chunks"][0]["content"]
