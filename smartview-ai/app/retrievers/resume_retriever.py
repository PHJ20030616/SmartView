"""带服务端用户隔离和 MySQL 降级的简历检索器。"""

from __future__ import annotations

import logging
from typing import Any, Mapping

from sqlalchemy import Engine, text

from app.core.config import Settings, get_settings
from app.services.resume_vectorizer import (
    build_chroma_collection,
    build_mysql_engine,
)

log = logging.getLogger(__name__)


class ResumeRetriever:
    """只接受服务端已解析出的用户与画像身份，不信任前端隔离字段。"""

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        engine: Engine | Any | None = None,
        collection: Any | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.engine = engine or build_mysql_engine(self.settings)
        self.collection = collection or build_chroma_collection(self.settings)

    def retrieve(
        self,
        query: str,
        *,
        user_id: int,
        resume_profile_id: int,
        top_k: int = 5,
    ) -> dict[str, Any]:
        """检索当前用户当前画像版本；异常或无结果时返回 MySQL 完整简历。"""
        profile = self._load_owned_confirmed_profile(user_id, resume_profile_id)
        profile_version = int(profile["version"])
        # Chroma 的多字段条件必须显式使用 $and；三个隔离字段都来自认证用户
        # 和 MySQL 当前画像，前端没有传入或覆盖这些条件的入口。
        where = {
            "$and": [
                {"user_id": int(profile["user_id"])},
                {"resume_profile_id": int(profile["id"])},
                {"profile_version": profile_version},
            ]
        }
        try:
            result = self.collection.query(
                query_texts=[query],
                n_results=max(1, min(int(top_k), 20)),
                where=where,
            )
            chunks = _flatten_query_result(result)
            if chunks:
                return {
                    "source": "chroma",
                    "degraded": False,
                    "profileVersion": profile_version,
                    "chunks": chunks,
                }
        except Exception:
            # 向量库是加速层，不应阻断已经确认的画像和面试流程。
            log.exception(
                "Chroma 简历检索失败，降级读取 MySQL，profile_id=%s",
                resume_profile_id,
            )

        return {
            "source": "mysql",
            "degraded": True,
            "profileVersion": profile_version,
            "chunks": [
                {
                    "content": _build_mysql_fallback_document(profile),
                    "metadata": {
                        "user_id": int(profile["user_id"]),
                        "resume_profile_id": int(profile["id"]),
                        "profile_version": profile_version,
                    },
                    "distance": None,
                }
            ],
        }

    def _load_owned_confirmed_profile(
        self,
        user_id: int,
        resume_profile_id: int,
    ) -> Mapping[str, Any]:
        with self.engine.connect() as connection:
            row = connection.execute(
                text(
                    """
                    SELECT id, user_id, version, confirm_status,
                           candidate_name, raw_text, project_experience_json, skills_json
                    FROM resume_profile
                    WHERE id = :profile_id
                      AND user_id = :user_id
                      AND confirm_status = 'CONFIRMED'
                      AND deleted = 0
                    LIMIT 1
                    """
                ),
                {"profile_id": int(resume_profile_id), "user_id": int(user_id)},
            ).mappings().first()
        if row is None:
            raise ValueError("简历画像不存在、未确认或不属于当前用户")
        return row


def _flatten_query_result(result: Mapping[str, Any]) -> list[dict[str, Any]]:
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


def _build_mysql_fallback_document(profile: Mapping[str, Any]) -> str:
    """将完整画像拼成面试上下文，确保向量库不可用时仍能继续面试。"""
    parts = [
        f"候选人姓名：{profile.get('candidate_name') or ''}",
        f"简历原文：{profile.get('raw_text') or ''}",
        f"项目经历：{profile.get('project_experience_json') or ''}",
        f"技能描述：{profile.get('skills_json') or ''}",
    ]
    return "\n".join(part for part in parts if part.split("：", 1)[1].strip())


def retrieve_resume_context(
    query: str,
    *,
    user_id: int,
    resume_profile_id: int,
    top_k: int = 5,
    settings: Settings | None = None,
) -> dict[str, Any]:
    """业务层便捷入口，隔离条件仍由服务端参数生成。"""
    return ResumeRetriever(settings).retrieve(
        query,
        user_id=user_id,
        resume_profile_id=resume_profile_id,
        top_k=top_k,
    )
