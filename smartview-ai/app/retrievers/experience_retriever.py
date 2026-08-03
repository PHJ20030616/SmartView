"""面经案例检索器：复用八股检索器的过滤与结果处理逻辑，仅切换 collection。"""

from __future__ import annotations

from typing import Any, Sequence

from app.clients.chroma_client import get_experience_collection
from app.core.config import Settings
from app.retrievers.knowledge_retriever import KnowledgeRetriever


class ExperienceRetriever(KnowledgeRetriever):
    """从面经案例 collection（interview_experience_cases）检索材料。"""

    def _collection_name(self) -> str:
        return self.settings.chroma_experience_collection_name

    def _build_collection(self) -> Any:
        return get_experience_collection(self.settings)


def retrieve_experience(
    query: str,
    *,
    role_direction: str | None = None,
    tags: Sequence[str] | None = None,
    keyword: str | None = None,
    top_k: int = 5,
    settings: Settings | None = None,
) -> dict[str, Any]:
    """业务层便捷入口，默认检索面经案例 collection。"""
    return ExperienceRetriever(settings).retrieve(
        query,
        role_direction=role_direction,
        tags=tags,
        keyword=keyword,
        top_k=top_k,
    )
