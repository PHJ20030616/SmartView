"""面经检索器测试：复用八股检索逻辑，但必须切换为面经 collection。"""

from __future__ import annotations

from unittest.mock import Mock, patch

from app.core.config import Settings
from app.retrievers.experience_retriever import (
    ExperienceRetriever,
    retrieve_experience,
)


def _make_settings() -> Settings:
    return Settings(chroma_experience_collection_name="interview_experience_cases")


def test_experience_retriever_uses_experience_collection_name() -> None:
    """子类必须覆盖 collection 名称，指向面经库。"""
    retriever = ExperienceRetriever(
        _make_settings(),
        collection=Mock(),
    )

    assert retriever.collection_name == "interview_experience_cases"


def test_experience_retriever_builds_collection_via_experience_factory() -> None:
    """未注入 collection 时应走面经 collection 工厂，而不是八股工厂。"""
    with patch(
        "app.retrievers.experience_retriever.get_experience_collection"
    ) as factory:
        ExperienceRetriever(_make_settings())

        factory.assert_called_once()


def test_experience_retrieve_returns_chunks_from_experience_collection() -> None:
    """查询结果应标注面经 collection，并将切片扁平化返回。"""
    collection = Mock()
    collection.query.return_value = {
        "documents": [["RAG 项目落地经验"]],
        "metadatas": [[{"title": "Agent 项目"}]],
        "distances": [[0.15]],
    }
    retriever = ExperienceRetriever(_make_settings(), collection=collection)

    result = retriever.retrieve("RAG", keyword="落地", top_k=3)

    collection.query.assert_called_once_with(
        query_texts=["RAG"],
        n_results=3,
        where=None,
        where_document={"$contains": "落地"},
    )
    assert result["collection"] == "interview_experience_cases"
    assert result["chunks"][0]["content"] == "RAG 项目落地经验"


def test_module_level_retrieve_experience_uses_experience_collection() -> None:
    """模块级便捷入口应构造 ExperienceRetriever 并透传过滤参数。"""
    with patch(
        "app.retrievers.experience_retriever.ExperienceRetriever"
    ) as retriever_cls:
        retriever_cls.return_value.retrieve.return_value = {
            "collection": "interview_experience_cases",
            "chunks": [],
        }

        result = retrieve_experience(
            "Agent",
            role_direction="AGENT_DEVELOPMENT",
            settings=_make_settings(),
        )

        retriever_cls.assert_called_once_with(_make_settings())
        retriever_cls.return_value.retrieve.assert_called_once_with(
            "Agent",
            role_direction="AGENT_DEVELOPMENT",
            tags=None,
            keyword=None,
            top_k=5,
        )
        assert result["collection"] == "interview_experience_cases"
