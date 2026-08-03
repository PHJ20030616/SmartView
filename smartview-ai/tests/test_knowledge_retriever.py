"""八股知识检索器的过滤参数透传、结果扁平化与异常降级测试。"""

from __future__ import annotations

from unittest.mock import Mock, patch

from app.core.config import Settings
from app.retrievers.knowledge_retriever import (
    KnowledgeRetriever,
    retrieve_knowledge,
)


def _make_settings() -> Settings:
    return Settings(chroma_knowledge_collection_name="interview_knowledge_base")


def _make_result() -> dict:
    return {
        "documents": [["Redis 缓存穿透的解决方案"]],
        "metadatas": [
            [{"title": "缓存穿透", "tags": ["Redis", "缓存"]}]
        ],
        "distances": [[0.2]],
    }


def test_retrieve_passes_query_and_combined_filters() -> None:
    """查询文本、top_k、元信息过滤与全文关键词过滤应原样传给 Chroma。"""
    collection = Mock()
    collection.query.return_value = _make_result()
    retriever = KnowledgeRetriever(_make_settings(), collection=collection)

    result = retriever.retrieve(
        "缓存穿透怎么解决",
        role_direction="JAVA_BACKEND",
        tags=["Redis", "缓存"],
        keyword="穿透",
        top_k=5,
    )

    collection.query.assert_called_once_with(
        query_texts=["缓存穿透怎么解决"],
        n_results=5,
        where={
            "$and": [
                {"role_direction": "JAVA_BACKEND"},
                {
                    "$or": [
                        {"tags": {"$contains": "Redis"}},
                        {"tags": {"$contains": "缓存"}},
                    ]
                },
            ]
        },
        where_document={"$contains": "穿透"},
    )
    assert result["source"] == "chroma"
    assert result["degraded"] is False
    assert result["collection"] == "interview_knowledge_base"
    assert result["chunks"][0]["content"] == "Redis 缓存穿透的解决方案"
    assert result["chunks"][0]["metadata"]["title"] == "缓存穿透"


def test_retrieve_without_filters_passes_none() -> None:
    """无过滤条件时 where 与 where_document 均为 None，top_k 需做边界钳制。"""
    collection = Mock()
    collection.query.return_value = _make_result()
    retriever = KnowledgeRetriever(_make_settings(), collection=collection)

    retriever.retrieve("Redis", top_k=0)

    collection.query.assert_called_once_with(
        query_texts=["Redis"],
        n_results=1,
        where=None,
        where_document=None,
    )


def test_retrieve_degrades_to_empty_chunks_on_chroma_error() -> None:
    """向量库异常不应阻断面试主流程，返回空切片并标记 degraded。"""
    collection = Mock()
    collection.query.side_effect = RuntimeError("Chroma 不可用")
    retriever = KnowledgeRetriever(_make_settings(), collection=collection)

    result = retriever.retrieve("Redis")

    assert result["source"] == "chroma"
    assert result["degraded"] is True
    assert result["chunks"] == []


def test_module_level_retrieve_knowledge_uses_default_collection() -> None:
    """模块级便捷入口默认检索八股 collection。"""
    with patch(
        "app.retrievers.knowledge_retriever.KnowledgeRetriever"
    ) as retriever_cls:
        retriever_cls.return_value.retrieve.return_value = {
            "collection": "interview_knowledge_base",
            "chunks": [],
        }
        result = retrieve_knowledge(
            "Redis",
            settings=_make_settings(),
            tags=["Redis"],
        )

        retriever_cls.assert_called_once_with(_make_settings())
        retriever_cls.return_value.retrieve.assert_called_once_with(
            "Redis",
            role_direction=None,
            tags=["Redis"],
            keyword=None,
            top_k=5,
        )
        assert result["collection"] == "interview_knowledge_base"
