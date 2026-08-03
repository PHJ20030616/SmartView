"""八股知识检索器：语义查询 + 方向/标签/关键词过滤。

八股与面经检索逻辑完全一致，只是 collection 不同，因此面经检索器
（experience_retriever.py）直接复用本模块的 KnowledgeRetriever 基类。
"""

from __future__ import annotations

import logging
from typing import Any, Sequence

from app.clients.chroma_client import (
    build_metadata_filter,
    flatten_query_result,
    get_knowledge_collection,
)
from app.core.config import Settings, get_settings

log = logging.getLogger(__name__)


class KnowledgeRetriever:
    """从八股知识 collection 检索材料，支持按面试方向、标签和关键词过滤。"""

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        collection: Any | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.collection_name = self._collection_name()
        self.collection = collection or self._build_collection()

    def _collection_name(self) -> str:
        """子类覆盖：返回自身使用的 Chroma collection 名称。"""
        return self.settings.chroma_knowledge_collection_name

    def _build_collection(self) -> Any:
        """子类覆盖：返回自身使用的 collection 对象，便于测试注入替身。"""
        return get_knowledge_collection(self.settings)

    def retrieve(
        self,
        query: str,
        *,
        role_direction: str | None = None,
        tags: Sequence[str] | None = None,
        keyword: str | None = None,
        top_k: int = 5,
    ) -> dict[str, Any]:
        """检索知识切片。

        参数说明：
        - query：语义检索文本（向量相似度）
        - role_direction：面试方向等值过滤，如 JAVA_BACKEND
        - tags：标签过滤，多个标签取并集（$or）
        - keyword：文档全文关键词过滤（$contains），与元信息过滤叠加生效
        """
        where = build_metadata_filter(
            role_direction=role_direction,
            tags=tags,
        )
        where_document = {"$contains": keyword} if keyword else None
        # top_k 钳制属于参数规范化，放在 try 之外，避免自身参数错误被误判为向量库降级
        n_results = max(1, min(int(top_k), 50))
        try:
            result = self.collection.query(
                query_texts=[query],
                n_results=n_results,
                where=where,
                where_document=where_document,
            )
        except Exception:
            # 知识库只是检索加速层，向量库异常不应阻断面试主流程，
            # 返回空结果并由上层决定降级策略。
            log.exception(
                "Chroma 知识检索失败 collection=%s query=%s",
                self.collection_name,
                query,
            )
            return {
                "source": "chroma",
                "degraded": True,
                "collection": self.collection_name,
                "query": query,
                "filter": {
                    "roleDirection": role_direction,
                    "tags": list(tags) if tags else None,
                    "keyword": keyword,
                },
                "chunks": [],
            }

        return {
            "source": "chroma",
            "degraded": False,
            "collection": self.collection_name,
            "query": query,
            "filter": {
                "roleDirection": role_direction,
                "tags": list(tags) if tags else None,
                "keyword": keyword,
            },
            "chunks": flatten_query_result(result),
        }


def retrieve_knowledge(
    query: str,
    *,
    role_direction: str | None = None,
    tags: Sequence[str] | None = None,
    keyword: str | None = None,
    top_k: int = 5,
    settings: Settings | None = None,
) -> dict[str, Any]:
    """业务层便捷入口，默认检索八股知识 collection。"""
    return KnowledgeRetriever(settings).retrieve(
        query,
        role_direction=role_direction,
        tags=tags,
        keyword=keyword,
        top_k=top_k,
    )
