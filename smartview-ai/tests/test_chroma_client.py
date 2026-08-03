"""Chroma 统一客户端的过滤条件构建与查询结果扁平化测试。"""

from __future__ import annotations

from app.clients.chroma_client import build_metadata_filter, flatten_query_result


def test_build_metadata_filter_returns_none_without_conditions() -> None:
    """无任何过滤条件时返回 None，避免向 Chroma 传递空 where。"""
    assert build_metadata_filter() is None
    assert build_metadata_filter(role_direction="", tags=[]) is None


def test_build_metadata_filter_single_role_direction() -> None:
    """方向是标量字段，直接使用等值匹配。"""
    assert build_metadata_filter(role_direction="JAVA_BACKEND") == {
        "role_direction": "JAVA_BACKEND"
    }


def test_build_metadata_filter_single_tag_uses_contains() -> None:
    """tags 以数组形式存储，Chroma 的 $in 对数组字段不生效，单标签用 $contains。"""
    assert build_metadata_filter(tags=["Redis"]) == {
        "tags": {"$contains": "Redis"}
    }


def test_build_metadata_filter_multiple_tags_use_or() -> None:
    """多标签之间是“任一命中”语义，组合为 $or。"""
    assert build_metadata_filter(tags=["Redis", "缓存"]) == {
        "$or": [
            {"tags": {"$contains": "Redis"}},
            {"tags": {"$contains": "缓存"}},
        ]
    }


def test_build_metadata_filter_ignores_empty_tags() -> None:
    """空字符串标签会被剔除，不参与条件构造。"""
    assert build_metadata_filter(tags=["Redis", "", None]) == {
        "tags": {"$contains": "Redis"}
    }


def test_build_metadata_filter_combines_direction_and_tags() -> None:
    """方向与标签同时存在时用 $and 组合。"""
    assert build_metadata_filter(
        role_direction="AGENT_DEVELOPMENT",
        tags=["RAG", "LangGraph"],
    ) == {
        "$and": [
            {"role_direction": "AGENT_DEVELOPMENT"},
            {
                "$or": [
                    {"tags": {"$contains": "RAG"}},
                    {"tags": {"$contains": "LangGraph"}},
                ]
            },
        ]
    }


def test_flatten_query_result_returns_chunks_in_order() -> None:
    """Chroma 列式结果应被扁平化为按距离排序的切片列表。"""
    result = {
        "documents": [["内容一", "内容二"]],
        "metadatas": [
            [
                {"title": "题一", "tags": ["Redis"]},
                {"title": "题二", "tags": ["缓存"]},
            ]
        ],
        "distances": [[0.1, 0.3]],
    }

    chunks = flatten_query_result(result)

    assert len(chunks) == 2
    assert chunks[0]["content"] == "内容一"
    assert chunks[0]["metadata"]["tags"] == ["Redis"]
    assert chunks[0]["distance"] == 0.1
    assert chunks[1]["content"] == "内容二"


def test_flatten_query_result_handles_missing_metadata_and_distance() -> None:
    """元信息或距离缺失时使用空字典/None 兜底，避免索引越界。"""
    chunks = flatten_query_result({"documents": [["只有正文"]]})

    assert len(chunks) == 1
    assert chunks[0]["metadata"] == {}
    assert chunks[0]["distance"] is None


def test_flatten_query_result_handles_empty_result() -> None:
    """无命中时返回空列表。"""
    assert flatten_query_result(
        {"documents": [[]], "metadatas": [[]], "distances": [[]]}
    ) == []
    assert flatten_query_result({}) == []
