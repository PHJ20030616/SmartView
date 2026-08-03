"""Chroma 统一客户端：知识库与面经库 collection 的创建、过滤条件构建和查询结果扁平化。

简历向量库（resume_profile_chunks）沿用 app/services/resume_vectorizer.py 中的
build_chroma_collection，本模块只负责知识库离线入库与检索相关的公共能力，
避免改动已有简历链路带来的回归风险。
"""

from __future__ import annotations

import logging
from typing import Any, Mapping, Sequence

from app.core.config import Settings, get_settings

log = logging.getLogger(__name__)


def get_chroma_client(settings: Settings) -> Any:
    """创建持久化 Chroma 客户端。

    chromadb 依赖较重且需要本地索引文件，延迟导入以便单元测试和
    不需要向量库的模块可以完全不依赖 chromadb。
    """
    import chromadb

    return chromadb.PersistentClient(path=settings.chroma_persist_directory)


def get_or_create_collection(settings: Settings, name: str) -> Any:
    """按名称获取或创建知识 collection，统一使用余弦距离。"""
    return get_chroma_client(settings).get_or_create_collection(
        name=name,
        metadata={"hnsw:space": "cosine"},
    )


def get_knowledge_collection(settings: Settings | None = None) -> Any:
    """获取八股知识 collection（interview_knowledge_base）。"""
    settings = settings or get_settings()
    return get_or_create_collection(settings, settings.chroma_knowledge_collection_name)


def get_experience_collection(settings: Settings | None = None) -> Any:
    """获取面经案例 collection（interview_experience_cases）。"""
    settings = settings or get_settings()
    return get_or_create_collection(settings, settings.chroma_experience_collection_name)


def build_metadata_filter(
    *,
    role_direction: str | None = None,
    tags: Sequence[str] | None = None,
) -> dict[str, Any] | None:
    """构造 Chroma where 过滤条件。

    tags 元信息以数组形式存储，Chroma 的 $in 对数组字段不生效，因此单标签使用
    $contains 判断数组是否包含该值，多标签之间是“任一命中”语义（$or）。
    role_direction 是标量字段，直接使用等值匹配。
    """
    conditions: list[dict[str, Any]] = []
    if role_direction:
        conditions.append({"role_direction": role_direction})

    normalized_tags = [tag for tag in (tags or []) if tag]
    if normalized_tags:
        # 单标签直接使用 $contains，避免构造只有一项的 $or 触发 Chroma 校验失败
        if len(normalized_tags) == 1:
            conditions.append({"tags": {"$contains": normalized_tags[0]}})
        else:
            conditions.append(
                {
                    "$or": [
                        {"tags": {"$contains": tag}} for tag in normalized_tags
                    ]
                }
            )

    if not conditions:
        return None
    if len(conditions) == 1:
        return conditions[0]
    return {"$and": conditions}


def flatten_query_result(result: Mapping[str, Any]) -> list[dict[str, Any]]:
    """把 Chroma query 返回的列式结果转成按条排列的切片列表。

    Chroma 对单条 query_texts 会返回嵌套一层的结果（documents[0] 等），
    这里统一展平，并兼容元信息或距离缺失的情况。
    """
    documents = (result.get("documents") or [[]])[0]
    metadatas = (result.get("metadatas") or [[]])[0]
    distances = (result.get("distances") or [[]])[0]

    chunks: list[dict[str, Any]] = []
    for index, document in enumerate(documents):
        chunks.append(
            {
                "content": document,
                "metadata": metadatas[index] if index < len(metadatas) else {},
                "distance": distances[index] if index < len(distances) else None,
            }
        )
    return chunks
