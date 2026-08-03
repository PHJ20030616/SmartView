"""已确认简历画像的切片与 Chroma 入库服务。"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any, Mapping
from urllib.parse import quote_plus

from sqlalchemy import Engine, create_engine, text

from app.core.config import Settings, get_settings
from app.core.errors import AppError

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ResumeChunk:
    """待写入 Chroma 的单个切片。"""

    chunk_id: str
    document: str
    metadata: dict[str, Any]


def build_mysql_engine(settings: Settings) -> Engine:
    """根据拆分配置创建 MySQL 引擎，避免把数据库凭据放入 MQ。"""
    username = quote_plus(settings.mysql_username)
    password = quote_plus(settings.mysql_password.get_secret_value())
    database = quote_plus(settings.mysql_database)
    url = (
        f"mysql+pymysql://{username}:{password}@"
        f"{settings.mysql_host}:{settings.mysql_port}/{database}?charset=utf8mb4"
    )
    return create_engine(url, pool_pre_ping=True, pool_recycle=1800)


def build_chroma_collection(settings: Settings) -> Any:
    """创建 Chroma collection；导入放在函数内以便单元测试无需启动 Chroma。

    embedding_function 固定传入 QwenEmbeddingFunction，与知识库 collection
    保持一致，统一使用 Qwen 文本向量模型编码。客户端连接方式复用
    get_chroma_client（默认连 Docker Chroma server），保证简历向量与知识库
    同库存储，Walnut UI 可直接观察同一份数据。
    """
    from app.clients.chroma_client import get_chroma_client
    from app.clients.qwen_embedding import QwenEmbeddingFunction

    client = get_chroma_client(settings)
    return client.get_or_create_collection(
        name=settings.chroma_collection_name,
        metadata={"hnsw:space": "cosine"},
        embedding_function=QwenEmbeddingFunction(settings),
    )


class ResumeVectorizer:
    """从 MySQL 读取已确认画像并写入简历切片向量。"""

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

    def vectorize(self, resume_profile_id: str, profile_version: int) -> int:
        """清理旧切片并写入当前画像版本，返回实际写入的切片数量。"""
        log.info(
            "开始向量化简历画像 profile_id=%s version=%s",
            resume_profile_id,
            profile_version,
        )
        try:
            try:
                profile = self._load_confirmed_profile(
                    resume_profile_id,
                    profile_version,
                )
            except AppError as exc:
                if exc.code == "RESUME_PROFILE_VERSION_STALE":
                    # 迟到的旧任务只能清理自己的 profile_id，绝不能按 resume_file_id
                    # 扫描删除当前版本；清理失败时抛出可重试错误，等待下一轮补偿。
                    self.delete_profile_vectors(int(resume_profile_id))
                raise
            chunks = self._build_chunks(profile)
            if not chunks:
                raise AppError(
                    "简历画像没有可用于向量化的原文、项目经历或技能描述",
                    code="VECTOR_SOURCE_EMPTY",
                )

            # 同一画像 ID 的旧版本需要清理；重新解析通常会生成新的画像 ID，
            # 因此还要按 user_id + resume_file_id 清理同一份简历的旧画像向量。
            self.delete_resume_file_vectors(
                user_id=int(profile["user_id"]),
                resume_file_id=int(profile["resume_file_id"]),
                keep_profile_id=int(profile["id"]),
                keep_profile_version=int(profile["version"]),
            )
            # 先删除同一画像的所有历史版本，保证版本更新后不会串出旧内容。
            # 画像确认状态仍由 Spring/MySQL 维护，Chroma 失败只会让本次任务失败。
            self.delete_profile_vectors(int(profile["id"]))
            self.collection.upsert(
                ids=[chunk.chunk_id for chunk in chunks],
                documents=[chunk.document for chunk in chunks],
                metadatas=[chunk.metadata for chunk in chunks],
            )
            try:
                # 新版本可能在本次写入期间被确认。若当前任务已经过期，
                # 删除本任务刚写入的切片，避免旧任务迟到后重新污染向量库。
                self._load_confirmed_profile(
                    resume_profile_id,
                    profile_version,
                )
            except AppError as exc:
                if exc.code == "RESUME_PROFILE_VERSION_STALE":
                    self.delete_profile_vectors(int(profile["id"]))
                raise
            log.info(
                "简历画像向量化完成 profile_id=%s version=%s chunks=%s",
                resume_profile_id,
                profile_version,
                len(chunks),
            )
            return len(chunks)
        except AppError:
            raise
        except Exception as exc:
            log.exception(
                "简历向量入库依赖异常，profile_id=%s, profile_version=%s",
                resume_profile_id,
                profile_version,
            )
            raise AppError(
                "简历向量入库依赖暂时不可用，请稍后重试",
                code="VECTOR_STORE_UNAVAILABLE",
            ) from exc

    def delete_profile_vectors(self, resume_profile_id: int) -> None:
        """删除某个画像的全部版本切片，供删除和重新解析流程复用。"""
        self.collection.delete(
            where={"resume_profile_id": int(resume_profile_id)},
        )

    def delete_resume_file_vectors(
        self,
        *,
        user_id: int,
        resume_file_id: int,
        keep_profile_id: int | None = None,
        keep_profile_version: int | None = None,
    ) -> None:
        """清理同一用户同一简历文件关联的旧画像向量。

        Chroma metadata 按验收要求只依赖画像隔离字段，未额外把
        resume_file_id 写入检索过滤条件；这里先从 MySQL 找到旧画像 ID，
        再按画像 ID 删除，避免误删该用户其他简历的向量。
        """
        with self.engine.connect() as connection:
            rows = connection.execute(
                text(
                    """
                    SELECT id
                    FROM resume_profile
                    WHERE user_id = :user_id
                      AND resume_file_id = :resume_file_id
                      AND (:keep_profile_id IS NULL OR id <> :keep_profile_id)
                      AND (
                          :keep_profile_version IS NULL
                          OR version < :keep_profile_version
                      )
                    """
                ),
                {
                    "user_id": int(user_id),
                    "resume_file_id": int(resume_file_id),
                    "keep_profile_id": (
                        None if keep_profile_id is None else int(keep_profile_id)
                    ),
                    "keep_profile_version": (
                        None
                        if keep_profile_version is None
                        else int(keep_profile_version)
                    ),
                },
            ).mappings()
            old_profile_ids = [int(row["id"]) for row in rows]

        for profile_id in old_profile_ids:
            self.delete_profile_vectors(profile_id)

    def cleanup_old_versions(
        self,
        resume_profile_id: int,
        keep_profile_version: int,
    ) -> None:
        """只保留指定版本，兼容历史数据已经写入向量库的场景。"""
        result = self.collection.get(
            where={"resume_profile_id": int(resume_profile_id)},
            include=["metadatas"],
        )
        stale_ids: list[str] = []
        for chunk_id, metadata in zip(
            result.get("ids", []),
            result.get("metadatas", []),
        ):
            if not metadata or int(metadata.get("profile_version", -1)) != int(
                keep_profile_version
            ):
                stale_ids.append(chunk_id)
        if stale_ids:
            self.collection.delete(ids=stale_ids)

    def _load_confirmed_profile(
        self,
        resume_profile_id: str,
        profile_version: int,
    ) -> Mapping[str, Any]:
        """只读取已确认且未删除的画像，避免未确认数据进入向量库。"""
        with self.engine.connect() as connection:
            row = connection.execute(
                text(
                    """
                    SELECT id, user_id, resume_file_id, version, confirm_status, deleted,
                           raw_text, project_experience_json, skills_json
                    FROM resume_profile
                    WHERE id = :profile_id
                      AND deleted = 0
                    LIMIT 1
                    """
                ),
                {"profile_id": int(resume_profile_id)},
            ).mappings().first()

        if row is None:
            raise AppError(
                "简历画像不存在或已删除",
                code="RESUME_PROFILE_NOT_FOUND",
            )
        if row["confirm_status"] != "CONFIRMED":
            raise AppError(
                "简历画像尚未确认，不能进行向量入库",
                code="RESUME_PROFILE_NOT_CONFIRMED",
            )
        if int(row["version"]) != int(profile_version):
            raise AppError(
                "简历画像版本已更新，当前任务已失效",
                code="RESUME_PROFILE_VERSION_STALE",
            )
        with self.engine.connect() as connection:
            newer_confirmed = connection.execute(
                text(
                    """
                    SELECT 1
                    FROM resume_profile newer_profile
                    WHERE newer_profile.resume_file_id = :resume_file_id
                      AND newer_profile.deleted = 0
                      AND newer_profile.confirm_status = 'CONFIRMED'
                      AND (
                          newer_profile.version > :profile_version
                          OR (
                              newer_profile.version = :profile_version
                              AND newer_profile.id > :profile_id
                          )
                      )
                    LIMIT 1
                    """
                ),
                {
                    "resume_file_id": int(row["resume_file_id"]),
                    "profile_version": int(row["version"]),
                    "profile_id": int(row["id"]),
                },
            ).first()
        if newer_confirmed is not None:
            raise AppError(
                "简历画像版本已更新，当前任务已失效",
                code="RESUME_PROFILE_VERSION_STALE",
            )
        return row

    def _build_chunks(self, profile: Mapping[str, Any]) -> list[ResumeChunk]:
        profile_id = int(profile["id"])
        user_id = int(profile["user_id"])
        profile_version = int(profile["version"])
        base_metadata = {
            "user_id": user_id,
            "resume_profile_id": profile_id,
            "profile_version": profile_version,
        }
        chunks: list[ResumeChunk] = []

        raw_text = str(profile.get("raw_text") or "").strip()
        for index, document in enumerate(
            _split_text(
                raw_text,
                self.settings.resume_vector_chunk_size,
                self.settings.resume_vector_chunk_overlap,
            )
        ):
            chunks.append(
                self._make_chunk(
                    profile_id,
                    profile_version,
                    "raw_text",
                    index,
                    document,
                    base_metadata,
                )
            )

        projects = _parse_json(profile.get("project_experience_json"), [])
        if isinstance(projects, list):
            for project_index, project in enumerate(projects):
                document = _render_project(project, project_index)
                for index, piece in enumerate(
                    _split_text(
                        document,
                        self.settings.resume_vector_chunk_size,
                        self.settings.resume_vector_chunk_overlap,
                    )
                ):
                    chunks.append(
                        self._make_chunk(
                            profile_id,
                            profile_version,
                            "project",
                            project_index * 10_000 + index,
                            piece,
                            base_metadata,
                        )
                    )

        skills = _parse_json(profile.get("skills_json"), [])
        for skill_index, skill in enumerate(_iter_skill_values(skills)):
            document = _render_skill(skill, skill_index)
            for index, piece in enumerate(
                _split_text(
                    document,
                    self.settings.resume_vector_chunk_size,
                    self.settings.resume_vector_chunk_overlap,
                )
            ):
                chunks.append(
                    self._make_chunk(
                        profile_id,
                        profile_version,
                        "skill",
                        skill_index * 10_000 + index,
                        piece,
                        base_metadata,
                    )
                )
        return chunks

    @staticmethod
    def _make_chunk(
        profile_id: int,
        profile_version: int,
        chunk_type: str,
        index: int,
        document: str,
        base_metadata: Mapping[str, Any],
    ) -> ResumeChunk:
        metadata = {
            **base_metadata,
            "chunk_type": chunk_type,
            "chunk_index": index,
        }
        chunk_id = f"resume:{profile_id}:v{profile_version}:{chunk_type}:{index}"
        return ResumeChunk(chunk_id=chunk_id, document=document, metadata=metadata)


def _split_text(text_value: str, chunk_size: int, overlap: int) -> list[str]:
    """按字符切片；重叠值过大时收敛到 size-1，避免死循环。"""
    text_value = text_value.strip()
    if not text_value:
        return []
    size = max(1, int(chunk_size))
    effective_overlap = min(max(0, int(overlap)), max(0, size - 1))
    chunks: list[str] = []
    start = 0
    while start < len(text_value):
        end = min(len(text_value), start + size)
        piece = text_value[start:end].strip()
        if piece:
            chunks.append(piece)
        if end >= len(text_value):
            break
        start = end - effective_overlap
    return chunks


def _parse_json(value: Any, default: Any) -> Any:
    if value is None or value == "":
        return default
    if isinstance(value, (dict, list)):
        return value
    try:
        return json.loads(value)
    except (TypeError, ValueError, json.JSONDecodeError):
        log.warning("简历画像 JSON 字段无法解析，将跳过该字段")
        return default


def _render_project(project: Any, index: int) -> str:
    if not isinstance(project, Mapping):
        return f"项目经历 {index + 1}：{_render_value(project)}"
    values = [
        f"项目名称：{_render_value(project.get('projectName') or project.get('name'))}",
        f"角色：{_render_value(project.get('role') or project.get('position'))}",
        f"项目描述：{_render_value(project.get('description'))}",
        f"技术栈：{_render_value(project.get('techStack'))}",
        f"职责：{_render_value(project.get('responsibilities'))}",
        f"成果：{_render_value(project.get('achievements'))}",
    ]
    return f"项目经历 {index + 1}\n" + "\n".join(
        value for value in values if not value.endswith("：")
    )


def _iter_skill_values(skills: Any) -> list[Any]:
    if isinstance(skills, list):
        return skills
    if isinstance(skills, Mapping):
        values: list[Any] = []
        for category, items in skills.items():
            if isinstance(items, list):
                values.extend(f"{category}：{_render_value(item)}" for item in items)
            else:
                values.append(f"{category}：{_render_value(items)}")
        return values
    if skills:
        return [skills]
    return []


def _render_skill(skill: Any, index: int) -> str:
    if isinstance(skill, Mapping):
        return f"技能描述 {index + 1}：{_render_value(skill)}"
    return f"技能描述 {index + 1}：{_render_value(skill)}"


def _render_value(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, Mapping):
        return "；".join(
            f"{key}={_render_value(item)}"
            for key, item in value.items()
            if _render_value(item)
        )
    if isinstance(value, (list, tuple, set)):
        return "、".join(_render_value(item) for item in value if _render_value(item))
    return str(value).strip()


def vectorize_resume_profile(
    resume_profile_id: str,
    profile_version: int,
    *,
    settings: Settings | None = None,
) -> int:
    """Worker 使用的便捷入口。"""
    return ResumeVectorizer(settings).vectorize(resume_profile_id, profile_version)


def delete_resume_profile_vectors(
    resume_profile_id: str,
    *,
    settings: Settings | None = None,
) -> None:
    """Worker 使用的删除入口；按画像 ID 幂等清理全部历史版本切片。"""
    ResumeVectorizer(settings).delete_profile_vectors(int(resume_profile_id))
