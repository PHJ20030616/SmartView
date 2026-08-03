"""离线入库脚本测试：切片逻辑、分集合入库、幂等策略与清理。"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

from app.core.config import Settings
from scripts.ingest_knowledge import (
    IngestSummary,
    _build_chunk_records,
    _split_by_chars,
    chunk_markdown,
    ingest_knowledge_root,
)


INGEST_SCRIPT = (
    Path(__file__).resolve().parents[2]
    / "smartview-ai"
    / "scripts"
    / "ingest_knowledge.py"
)

KB_FRONTMATTER = """---
title: Redis 速记
category: 缓存
source_type: KNOWLEDGE_BASE
role_direction: JAVA_BACKEND
tags: [Redis, 缓存]
---
"""

EXP_FRONTMATTER = """---
title: Agent 面经
category: Agent
source_type: EXPERIENCE_CASE
role_direction: AGENT_DEVELOPMENT
tags: [RAG, LangGraph]
---
"""


class FakeCollection:
    """模拟 Chroma collection 的内存替身，记录写入轨迹供断言。"""

    def __init__(self, name: str) -> None:
        self.name = name
        self.store: dict[str, dict[str, Any]] = {}
        self.upsert_calls: list[list[str]] = []
        self.add_calls: list[list[str]] = []
        self.delete_calls: list[list[str]] = []

    def upsert(
        self,
        *,
        ids: list[str],
        documents: list[str],
        metadatas: list[dict[str, Any]],
    ) -> None:
        self.upsert_calls.append(list(ids))
        for chunk_id, document, metadata in zip(ids, documents, metadatas):
            self.store[chunk_id] = {"document": document, "metadata": metadata}

    def add(
        self,
        *,
        ids: list[str],
        documents: list[str],
        metadatas: list[dict[str, Any]],
    ) -> None:
        self.add_calls.append(list(ids))
        for chunk_id, document, metadata in zip(ids, documents, metadatas):
            self.store[chunk_id] = {"document": document, "metadata": metadata}

    def get(self, *, ids: list[str] | None = None, include: list[str] | None = None):
        items = (
            list(self.store.items())
            if ids is None
            else [
                (chunk_id, value)
                for chunk_id, value in self.store.items()
                if chunk_id in set(ids)
            ]
        )
        if include and "metadatas" not in include:
            metadatas = [None] * len(items)
        else:
            metadatas = [value["metadata"] for _, value in items]
        return {"ids": [chunk_id for chunk_id, _ in items], "metadatas": metadatas}

    def delete(self, *, ids: list[str]) -> None:
        self.delete_calls.append(list(ids))
        for chunk_id in ids:
            self.store.pop(chunk_id, None)

    def count(self) -> int:
        return len(self.store)


def _make_settings() -> Settings:
    return Settings(
        chroma_knowledge_collection_name="interview_knowledge_base",
        chroma_experience_collection_name="interview_experience_cases",
    )


def _build_tree(tmp_path: Path) -> dict[str, Path]:
    """构造两个知识目录及一份八股、一份面经样例文件。"""
    kb_dir = tmp_path / "interview_knowledge_base"
    exp_dir = tmp_path / "interview_experience_cases"
    kb_dir.mkdir()
    exp_dir.mkdir()
    kb_path = kb_dir / "redis.md"
    exp_path = exp_dir / "agent.md"
    kb_path.write_text(
        KB_FRONTMATTER + "## **缓存穿透**\n\n布隆过滤器可以拦截不存在的 key。\n",
        encoding="utf-8",
    )
    exp_path.write_text(
        EXP_FRONTMATTER + "## RAG 落地\n\n用 LangGraph 编排检索与生成。\n",
        encoding="utf-8",
    )
    return {"kb": kb_path, "exp": exp_path}


def test_chunk_markdown_splits_by_headings_and_strips_frontmatter() -> None:
    """正文按标题切分，frontmatter 不进入切片，标题去除加粗装饰。"""
    text = KB_FRONTMATTER + """# 总览

开头内容

## **第一个问题**

内容一

## 第二个问题

内容二
"""

    chunks = chunk_markdown(text)

    assert [chunk["title"] for chunk in chunks] == ["总览", "第一个问题", "第二个问题"]
    assert "frontmatter" not in chunks[0]["content"]
    assert "开头内容" in chunks[0]["content"]
    assert "第一个问题" in chunks[1]["content"]


def test_chunk_markdown_splits_long_section_by_paragraphs() -> None:
    """超长小节优先按段落聚合，避免把一个完整段落拦腰截断。"""
    # 每段带唯一结束标记，便于断言切片没有在段落中间切断
    paragraphs = "\n\n".join(
        f"段落{i}：" + "长" * 40 + f"#尾{i}#" for i in range(40)
    )
    chunks = chunk_markdown(
        KB_FRONTMATTER + "## 长章节\n\n" + paragraphs,
        max_chars=300,
        overlap=30,
    )

    assert len(chunks) >= 2
    assert all(chunk["content"] for chunk in chunks)
    # 段落聚合的每个切片都应完整包含段落结束标记；标题单独成片除外
    for chunk in chunks:
        content = chunk["content"]
        if content.startswith("## "):
            continue
        assert re.search(r"#尾\d+#$", content), content[-30:]


def test_chunk_markdown_splits_overlong_paragraph_by_chars_with_overlap() -> None:
    """单个段落超长时退化为字符切片，且相邻切片有重叠内容。"""
    chunks = chunk_markdown(
        KB_FRONTMATTER + "## 超长段落\n\n" + "长" * 500,
        max_chars=200,
        overlap=20,
    )

    # 标题行会作为独立短段落先成片，其后才是 3 个字符切片
    assert len(chunks) == 4
    assert chunks[0]["content"] == "## 超长段落"
    assert all(len(chunk["content"]) <= 200 for chunk in chunks[1:])
    # 相邻字符切片之间保留 overlap 长度的重叠内容
    assert chunks[2]["content"].startswith(chunks[1]["content"][-20:])


def test_chunk_markdown_returns_empty_for_invalid_frontmatter() -> None:
    """缺少 frontmatter 时按数据异常处理，返回空切片。"""
    assert chunk_markdown("没有分隔符的正文\n") == []


def test_build_chunk_records_uses_deterministic_ids(tmp_path: Path) -> None:
    """chunk id 由前缀+相对路径+序号组成，重复运行保持稳定以支持幂等。"""
    md_path = tmp_path / "interview_knowledge_base" / "redis.md"
    md_path.parent.mkdir(parents=True)
    md_path.write_text(
        KB_FRONTMATTER + "## 缓存穿透\n\n布隆过滤器。\n",
        encoding="utf-8",
    )
    document = {
        "path": str(md_path),
        "relativePath": "interview_knowledge_base/redis.md",
        "metadata": {
            "title": "Redis 速记",
            "category": "缓存",
            "source_type": "KNOWLEDGE_BASE",
            "role_direction": "JAVA_BACKEND",
            "tags": ["Redis"],
        },
    }

    records = _build_chunk_records(
        document,
        prefix="kb",
        max_chars=1500,
        overlap=120,
    )

    assert records
    assert [record[0] for record in records] == [
        f"kb:interview_knowledge_base/redis.md:{index}"
        for index in range(len(records))
    ]
    assert all("content_hash" in record[2] for record in records)
    assert all(record[2]["source_type"] == "KNOWLEDGE_BASE" for record in records)


def test_ingest_separates_knowledge_and_experience_collections(tmp_path: Path) -> None:
    """八股与面经必须进入各自独立的 collection，chunk id 前缀不同。"""
    _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }

    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )

    assert summary.errors == []
    assert summary.valid_documents == 2
    assert summary.ingested_chunks == 2
    assert kb_collection.count() == 1
    assert exp_collection.count() == 1
    assert next(iter(kb_collection.store)).startswith("kb:")
    assert next(iter(exp_collection.store)).startswith("exp:")
    assert summary.collection_counts == {
        "interview_knowledge_base": 1,
        "interview_experience_cases": 1,
    }


def test_ingest_upsert_overwrites_existing_chunks(tmp_path: Path) -> None:
    """upsert 策略重复运行时用新内容覆盖同 id 切片。"""
    paths = _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }
    ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )
    original_id = next(iter(kb_collection.store))

    # 修改八股正文后重新入库，同 id 应被覆盖为最新内容
    paths["kb"].write_text(
        KB_FRONTMATTER + "## **缓存穿透**\n\n更新后的解决方案。\n",
        encoding="utf-8",
    )
    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )

    assert summary.ingested_chunks == 2
    assert kb_collection.store[original_id]["document"].endswith(
        "更新后的解决方案。"
    )
    # 面经内容未改动但同 id 也会被 upsert 覆盖，属于预期行为
    assert exp_collection.count() == 1


def test_ingest_skip_ignores_existing_chunks(tmp_path: Path) -> None:
    """skip 策略重复运行时跳过已存在切片，不重复写入。"""
    _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }
    ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )
    kb_collection.upsert_calls.clear()

    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
        strategy="skip",
    )

    assert summary.ingested_chunks == 0
    assert summary.skipped_chunks == 2
    assert kb_collection.upsert_calls == []
    assert kb_collection.add_calls == []
    assert kb_collection.count() == 1


def test_ingest_skip_adds_only_new_documents(tmp_path: Path) -> None:
    """skip 策略只补充新增文件，已存在文件保持不动。"""
    paths = _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }
    ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )

    # 新增一份八股文件后使用 skip：只写新文件，旧文件跳过
    new_path = paths["kb"].parent / "mq.md"
    new_path.write_text(
        KB_FRONTMATTER.replace("Redis 速记", "MQ 速记").replace(
            "tags: [Redis, 缓存]", "tags: [MQ]"
        )
        + "## 消息可靠性\n\n生产者确认。\n",
        encoding="utf-8",
    )
    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
        strategy="skip",
    )

    assert summary.ingested_chunks == 1
    assert summary.skipped_chunks == 2
    assert kb_collection.count() == 2
    assert any(chunk_id.startswith("kb:interview_knowledge_base/mq.md:")
               for chunk_id in kb_collection.store)


def test_ingest_dry_run_does_not_write(tmp_path: Path) -> None:
    """dry-run 只统计待入库内容，不调用 collection 写入。"""
    _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")

    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections={
            "KNOWLEDGE_BASE": kb_collection,
            "EXPERIENCE_CASE": exp_collection,
        },
        dry_run=True,
    )

    assert summary.ingested_chunks == 2
    assert kb_collection.count() == 0
    assert exp_collection.count() == 0
    assert summary.collection_counts == {}


def test_ingest_prune_removes_stale_chunks_when_file_deleted(
    tmp_path: Path,
) -> None:
    """删除某个文件后执行 --prune，应清理其遗留切片。"""
    paths = _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }
    ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )

    paths["kb"].unlink()
    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
        prune=True,
    )

    assert summary.pruned_chunks == 1
    assert kb_collection.count() == 0
    assert exp_collection.count() == 1


def test_ingest_prune_reports_missing_directory_without_cleaning(
    tmp_path: Path,
) -> None:
    """整个知识目录被删除时属于结构异常：扫描报错，prune 不擅自连接清理。"""
    _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }
    ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )

    shutil.rmtree(tmp_path / "interview_knowledge_base")
    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
        prune=True,
    )

    assert any("缺少知识目录" in error for error in summary.errors)
    # 缺少目录时不清理由该来源类型遗留的切片，避免错误 root 下误删数据
    assert kb_collection.count() == 1
    assert exp_collection.count() == 1


def test_ingest_prune_keeps_chunks_when_file_becomes_invalid(tmp_path: Path) -> None:
    """文件仍存在但暂时校验失败时，--prune 不应删除其历史切片。

    清理依据是“源文件是否仍存在”而不是“本次校验是否通过”，
    避免一次临时的 frontmatter 错误配合 --prune 导致整库数据丢失。
    """
    paths = _build_tree(tmp_path)
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }
    ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )
    assert kb_collection.count() == 1

    # 把八股文件 frontmatter 改坏：文件仍在，只是本次校验失败
    paths["kb"].write_text(
        KB_FRONTMATTER.replace("source_type: KNOWLEDGE_BASE", "source_type: UNKNOWN"),
        encoding="utf-8",
    )
    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
        prune=True,
    )

    assert any("source_type" in error for error in summary.errors)
    # 历史切片保留，不因临时校验失败被误删
    assert summary.pruned_chunks == 0
    assert kb_collection.count() == 1
    assert exp_collection.count() == 1


def test_ingest_prune_removes_orphan_chunks_when_file_shortened(
    tmp_path: Path,
) -> None:
    """文件内容缩短导致切片数减少时，--prune 应清理索引越界的孤儿切片。"""
    kb_dir = tmp_path / "interview_knowledge_base"
    exp_dir = tmp_path / "interview_experience_cases"
    kb_dir.mkdir()
    exp_dir.mkdir()
    kb_path = kb_dir / "redis.md"
    # 三个小节 → 三个切片
    kb_path.write_text(
        KB_FRONTMATTER
        + "\n\n".join(f"## 问题{i}\n\n内容{i}" for i in range(3)),
        encoding="utf-8",
    )
    (exp_dir / "agent.md").write_text(
        EXP_FRONTMATTER + "## RAG 落地\n\n用 LangGraph 编排。\n",
        encoding="utf-8",
    )
    kb_collection = FakeCollection("interview_knowledge_base")
    exp_collection = FakeCollection("interview_experience_cases")
    collections = {
        "KNOWLEDGE_BASE": kb_collection,
        "EXPERIENCE_CASE": exp_collection,
    }
    ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
    )
    assert kb_collection.count() == 3

    # 缩短为一个小节：索引越界的 :1/:2 孤儿切片应在 prune 时被清理
    kb_path.write_text(
        KB_FRONTMATTER + "## 唯一问题\n\n新内容\n",
        encoding="utf-8",
    )
    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections=collections,
        prune=True,
    )

    assert summary.pruned_chunks == 2
    assert kb_collection.count() == 1
    assert next(iter(kb_collection.store)).endswith(":0")


def test_split_by_chars_converges_when_overlap_exceeds_size() -> None:
    """overlap 大于等于 max_chars 时收敛为 max_chars-1，避免死循环。"""
    pieces = _split_by_chars("长" * 500, max_chars=200, overlap=500)

    # 能正常返回即证明未死循环；重叠被收敛到 max_chars-1，每片长度受限
    assert pieces
    assert all(len(piece) <= 200 for piece in pieces)
    assert pieces[1].startswith(pieces[0][-199:])


def test_ingest_reports_invalid_documents_without_writing(tmp_path: Path) -> None:
    """元信息非法的文档计入错误但不写入任何 collection。"""
    kb_dir = tmp_path / "interview_knowledge_base"
    exp_dir = tmp_path / "interview_experience_cases"
    kb_dir.mkdir()
    exp_dir.mkdir()
    (kb_dir / "bad.md").write_text(
        KB_FRONTMATTER.replace("source_type: KNOWLEDGE_BASE", "source_type: UNKNOWN"),
        encoding="utf-8",
    )
    kb_collection = FakeCollection("interview_knowledge_base")

    summary = ingest_knowledge_root(
        tmp_path,
        settings=_make_settings(),
        collections={"KNOWLEDGE_BASE": kb_collection},
    )

    assert summary.invalid_documents == 1
    assert summary.valid_documents == 0
    assert kb_collection.count() == 0
    assert any("source_type" in error for error in summary.errors)


def test_cli_dry_run_outputs_json(tmp_path: Path) -> None:
    """CLI --dry-run --json 输出结构化汇总，且不连接真实 Chroma。"""
    _build_tree(tmp_path)

    result = subprocess.run(
        [
            sys.executable,
            str(INGEST_SCRIPT),
            "--root",
            str(tmp_path),
            "--dry-run",
            "--json",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        check=False,
    )

    assert result.returncode == 0, (result.stdout or "") + (result.stderr or "")
    payload = json.loads(result.stdout)
    assert payload["validDocuments"] == 2
    assert payload["ingestedChunks"] == 2
    assert payload["errors"] == []


def test_cli_returns_nonzero_for_invalid_document(tmp_path: Path) -> None:
    """存在元信息错误时 CLI 返回非零退出码，与 inspect 脚本口径一致。"""
    kb_dir = tmp_path / "interview_knowledge_base"
    exp_dir = tmp_path / "interview_experience_cases"
    kb_dir.mkdir()
    exp_dir.mkdir()
    (kb_dir / "bad.md").write_text(
        KB_FRONTMATTER.replace("source_type: KNOWLEDGE_BASE", "source_type: UNKNOWN"),
        encoding="utf-8",
    )

    result = subprocess.run(
        [
            sys.executable,
            str(INGEST_SCRIPT),
            "--root",
            str(tmp_path),
            "--dry-run",
            "--json",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        check=False,
    )

    assert result.returncode == 1
    payload = json.loads(result.stdout)
    assert payload["invalidDocuments"] == 1
    assert payload["errors"]
