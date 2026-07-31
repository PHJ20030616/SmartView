import json
from datetime import datetime, timezone

import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.services.resume_vectorizer import ResumeVectorizer


class FakeResult:
    def __init__(self, rows: list[dict]) -> None:
        self.rows = rows

    def mappings(self):
        return self

    def first(self):
        return self.rows[0] if self.rows else None

    def __iter__(self):
        return iter(self.rows)


class FakeConnection:
    def __init__(self, profile: dict, old_profile_ids: list[dict]) -> None:
        self.profile = profile
        self.old_profile_ids = old_profile_ids
        self.executed: list[tuple[str, dict]] = []

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return None

    def execute(self, statement, params):
        sql = str(statement)
        self.executed.append((sql, params))
        if "SELECT id, user_id" in sql:
            return FakeResult([self.profile])
        if "SELECT 1" in sql:
            return FakeResult([])
        return FakeResult(self.old_profile_ids)


class FakeEngine:
    def __init__(self, profile: dict, old_profile_ids: list[dict] | None = None) -> None:
        self.profile = profile
        self.old_profile_ids = old_profile_ids or []
        self.connections: list[FakeConnection] = []

    def connect(self):
        connection = FakeConnection(self.profile, self.old_profile_ids)
        self.connections.append(connection)
        return connection


class FakeCollection:
    def __init__(self) -> None:
        self.deleted: list[dict] = []
        self.upserted: dict = {}

    def delete(self, **kwargs):
        self.deleted.append(kwargs)

    def upsert(self, **kwargs):
        self.upserted = kwargs


def _profile(*, confirm_status: str = "CONFIRMED") -> dict:
    return {
        "id": 12,
        "user_id": 7,
        "resume_file_id": 88,
        "version": 2,
        "confirm_status": confirm_status,
        "deleted": 0,
        "raw_text": "张三是一名 Python 后端工程师，负责招聘平台服务端开发。",
        "project_experience_json": json.dumps(
            [
                {
                    "projectName": "招聘平台",
                    "role": "核心开发",
                    "description": "负责服务端开发",
                    "techStack": ["Python", "FastAPI"],
                }
            ],
            ensure_ascii=False,
        ),
        "skills_json": json.dumps(["Python", "FastAPI"], ensure_ascii=False),
    }


def test_build_chunks_contains_required_isolation_metadata() -> None:
    vectorizer = ResumeVectorizer.__new__(ResumeVectorizer)
    vectorizer.settings = Settings(
        _env_file=None,
        resume_vector_chunk_size=18,
        resume_vector_chunk_overlap=4,
    )

    chunks = vectorizer._build_chunks(_profile())

    assert chunks
    assert {chunk.metadata["chunk_type"] for chunk in chunks} == {
        "raw_text",
        "project",
        "skill",
    }
    for chunk in chunks:
        assert chunk.metadata["user_id"] == 7
        assert chunk.metadata["resume_profile_id"] == 12
        assert chunk.metadata["profile_version"] == 2
        assert chunk.document


def test_vectorize_cleans_old_profile_and_upserts_current_chunks() -> None:
    collection = FakeCollection()
    vectorizer = ResumeVectorizer(
        Settings(_env_file=None),
        engine=FakeEngine(_profile(), [{"id": 10}, {"id": 11}]),
        collection=collection,
    )

    chunks_count = vectorizer.vectorize("12", 2)

    assert chunks_count > 0
    assert {"where": {"resume_profile_id": 10}} in collection.deleted
    assert {"where": {"resume_profile_id": 11}} in collection.deleted
    assert {"where": {"resume_profile_id": 12}} in collection.deleted
    assert len(collection.upserted["ids"]) == chunks_count
    assert all(
        metadata["profile_version"] == 2
        for metadata in collection.upserted["metadatas"]
    )


def test_unconfirmed_profile_is_rejected_before_chroma_write() -> None:
    collection = FakeCollection()
    vectorizer = ResumeVectorizer(
        Settings(_env_file=None),
        engine=FakeEngine(_profile(confirm_status="UNCONFIRMED")),
        collection=collection,
    )

    with pytest.raises(AppError) as exc_info:
        vectorizer.vectorize("12", 2)

    assert exc_info.value.code == "RESUME_PROFILE_NOT_CONFIRMED"
    assert collection.upserted == {}


def test_chroma_failure_is_converted_to_retryable_error() -> None:
    class BrokenCollection(FakeCollection):
        def delete(self, **kwargs):
            raise RuntimeError("chroma unavailable")

    vectorizer = ResumeVectorizer(
        Settings(_env_file=None),
        engine=FakeEngine(_profile()),
        collection=BrokenCollection(),
    )

    with pytest.raises(AppError) as exc_info:
        vectorizer.vectorize("12", 2)

    assert exc_info.value.code == "VECTOR_STORE_UNAVAILABLE"


def test_delete_profile_vectors_is_idempotent_for_missing_chunks() -> None:
    collection = FakeCollection()
    vectorizer = ResumeVectorizer(
        Settings(_env_file=None),
        engine=FakeEngine(_profile()),
        collection=collection,
    )

    vectorizer.delete_profile_vectors(12)
    vectorizer.delete_profile_vectors(12)

    assert collection.deleted == [
        {"where": {"resume_profile_id": 12}},
        {"where": {"resume_profile_id": 12}},
    ]


def test_old_version_cleanup_is_bounded_by_profile_version() -> None:
    engine = FakeEngine(_profile(), [{"id": 10}, {"id": 11}])
    vectorizer = ResumeVectorizer(
        Settings(_env_file=None),
        engine=engine,
        collection=FakeCollection(),
    )

    vectorizer.delete_resume_file_vectors(
        user_id=7,
        resume_file_id=88,
        keep_profile_id=12,
        keep_profile_version=2,
    )

    cleanup_query = engine.connections[0].executed[0]
    assert cleanup_query[1]["keep_profile_id"] == 12
    assert cleanup_query[1]["keep_profile_version"] == 2
    assert "version < :keep_profile_version" in cleanup_query[0]


def test_stale_profile_after_upsert_removes_its_own_chunks() -> None:
    class StaleAfterWriteVectorizer(ResumeVectorizer):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs)
            self.load_count = 0

        def _load_confirmed_profile(self, resume_profile_id, profile_version):
            self.load_count += 1
            if self.load_count == 1:
                return _profile()
            raise AppError(
                "简历画像版本已更新，当前任务已失效",
                code="RESUME_PROFILE_VERSION_STALE",
            )

    collection = FakeCollection()
    vectorizer = StaleAfterWriteVectorizer(
        Settings(_env_file=None),
        engine=FakeEngine(_profile()),
        collection=collection,
    )

    with pytest.raises(AppError) as exc_info:
        vectorizer.vectorize("12", 2)

    assert exc_info.value.code == "RESUME_PROFILE_VERSION_STALE"
    assert {"where": {"resume_profile_id": 12}} in collection.deleted
