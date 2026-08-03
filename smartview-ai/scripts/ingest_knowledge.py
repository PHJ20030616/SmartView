"""知识库离线入库脚本：把 knowledge/ 下的八股与面经 Markdown 切片写入 Chroma。

设计要点：
- 八股（KNOWLEDGE_BASE）与面经（EXPERIENCE_CASE）分别写入
  interview_knowledge_base / interview_experience_cases 两个独立 collection。
- chunk id 由“来源前缀 + 相对路径 + 序号”组成，天然确定且可复现，
  因此重复运行幂等：--strategy upsert 覆盖更新，--strategy skip 跳过已存在内容。
- 切片以 Markdown 标题为天然分界（一道题/一个主题一个切片），超长内容再按
  空行段落或字符二次切分，保证检索粒度贴近面试问题。

用法（在 smartview-ai 目录下执行）：
    python -m scripts.ingest_knowledge --root ../knowledge
    python -m scripts.ingest_knowledge --strategy skip --prune
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

# 同时支持 `python scripts/ingest_knowledge.py` 与 `python -m scripts.ingest_knowledge`
# 两种执行方式：直接执行时脚本目录在 sys.path，需要把 smartview-ai 也加入导入路径。
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.clients.chroma_client import (  # noqa: E402
    get_experience_collection,
    get_knowledge_collection,
)
from app.core.config import Settings, get_settings  # noqa: E402
from scripts.knowledge_metadata import (  # noqa: E402
    IGNORED_MARKDOWN_FILES,
    SOURCE_DIRECTORIES,
    extract_frontmatter_body,
    scan_knowledge_root,
)

log = logging.getLogger(__name__)

# chunk id 前缀：八股与面经分开，避免相同相对路径在不同集合间产生歧义
ID_PREFIX = {
    "KNOWLEDGE_BASE": "kb",
    "EXPERIENCE_CASE": "exp",
}

# Markdown 标题行是材料中一道题/一个主题的天然分界
_HEADING_RE = re.compile(r"^(#{1,4})\s+(.*)$")
# 标题写入元信息时去掉加粗等 Markdown 装饰符号，便于阅读和展示
_MARKDOWN_EMPHASIS_RE = re.compile(r"[*_`#]+")

# 单次写入/查询的批大小，避免超大文档一次性提交过多切片
_BATCH_SIZE = 100


@dataclass
class IngestSummary:
    """一次入库运行的统计结果。"""

    scanned: int = 0
    valid_documents: int = 0
    invalid_documents: int = 0
    ingested_chunks: int = 0
    skipped_chunks: int = 0
    pruned_chunks: int = 0
    collection_counts: dict[str, int] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)


def chunk_markdown(text: str, *, max_chars: int = 1500, overlap: int = 120) -> list[dict[str, str]]:
    """把 Markdown 正文按标题切分成检索切片。

    返回 [{"title": 小节标题（去装饰）, "content": 标题行 + 正文}]。
    frontmatter 会被剥离，不进入切片内容。
    """
    body, errors = extract_frontmatter_body(text)
    if errors:
        # 正常流程不会走到这里（扫描阶段已校验过 frontmatter），
        # 返回空列表由调用方按数据异常处理。
        return []

    chunks: list[dict[str, str]] = []
    for section_title, section_text in _split_sections(body):
        for piece in _split_section(section_text, max_chars=max_chars, overlap=overlap):
            chunks.append(
                {
                    "title": _clean_heading(section_title) if section_title else "",
                    "content": piece,
                }
            )
    return chunks


def ingest_knowledge_root(
    root: Path,
    settings: Settings | None = None,
    *,
    strategy: str = "upsert",
    prune: bool = False,
    dry_run: bool = False,
    max_chars: int = 1500,
    overlap: int = 120,
    collections: dict[str, Any] | None = None,
) -> IngestSummary:
    """扫描并入库知识根目录，返回运行汇总。

    collections 参数用于测试注入替身，键为 source_type（KNOWLEDGE_BASE /
    EXPERIENCE_CASE）；不传时按 settings 连接真实 Chroma。
    """
    settings = settings or get_settings()
    scan = scan_knowledge_root(root)
    summary = IngestSummary(scanned=len(scan.documents))
    summary.errors.extend(scan.errors)

    valid_documents = [doc for doc in scan.documents if not doc["errors"]]
    summary.valid_documents = len(valid_documents)
    summary.invalid_documents = len(scan.documents) - len(valid_documents)

    # 按材料来源分组，保证八股与面经进入各自独立的 collection
    by_source_type: dict[str, list[dict[str, Any]]] = {}
    for document in valid_documents:
        by_source_type.setdefault(document["metadata"]["source_type"], []).append(document)

    # 目录仍存在但已无有效文档时也要进入循环：配合 --prune 清理已删除文件的
    # 遗留切片。仅 prune 场景补全，避免普通入库为空的来源类型创建 collection。
    if prune:
        for source_type, directory_name in SOURCE_DIRECTORIES.items():
            if (root / directory_name).is_dir():
                by_source_type.setdefault(source_type, [])

    for source_type, documents in by_source_type.items():
        prefix = ID_PREFIX[source_type]
        collection_name = _collection_name_for(settings, source_type)

        if dry_run:
            # dry-run 不创建/连接 collection：只统计待入库切片，避免重开真实
            # collection 的副作用（例如与已持久化的旧 embedding 配置冲突）
            for document in documents:
                try:
                    records = _build_chunk_records(
                        document,
                        prefix=prefix,
                        max_chars=max_chars,
                        overlap=overlap,
                    )
                except (OSError, UnicodeError, ValueError) as exc:
                    # 单个文件异常不影响其他材料入库，但需要计入错误便于排查
                    summary.errors.append(f"{document['relativePath']}：{exc}")
                    continue
                summary.ingested_chunks += len(records)
            continue

        collection = (collections or {}).get(source_type) or _collection_factory(
            settings, source_type
        )
        # 本次校验通过文档的切片 id 与其相对路径，作为 prune 判定“多余切片”的依据
        valid_chunk_ids: set[str] = set()
        valid_relative_paths: set[str] = set()

        for document in documents:
            try:
                records = _build_chunk_records(
                    document,
                    prefix=prefix,
                    max_chars=max_chars,
                    overlap=overlap,
                )
            except (OSError, UnicodeError, ValueError) as exc:
                # 单个文件异常不影响其他材料入库，但需要计入错误便于排查
                summary.errors.append(f"{document['relativePath']}：{exc}")
                continue
            valid_chunk_ids.update(record[0] for record in records)
            valid_relative_paths.add(document["relativePath"])
            _write_chunks(collection, records, strategy=strategy, summary=summary)

        if prune:
            # 清理判据：源文件被删除→清理；文件仍在但本次校验失败→保留；
            # 文件校验通过→只保留本次生成的切片，索引越界的孤儿切片一并清理。
            existing_paths = _existing_relative_paths(root, source_type)
            summary.pruned_chunks += _prune_stale(
                collection,
                prefix,
                existing_paths=existing_paths,
                valid_ids=valid_chunk_ids,
                valid_paths=valid_relative_paths,
            )
        summary.collection_counts[collection_name] = collection.count()

    return summary


def _build_chunk_records(
    document: dict[str, Any],
    *,
    prefix: str,
    max_chars: int,
    overlap: int,
) -> list[tuple[str, str, dict[str, Any]]]:
    """读取文档正文并切片，返回 [(chunk_id, content, metadata), ...]。"""
    path = Path(document["path"])
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise ValueError(f"读取失败：{exc}") from exc

    metadata = document["metadata"]
    relative_path = document["relativePath"]
    chunks = chunk_markdown(text, max_chars=max_chars, overlap=overlap)
    if not chunks:
        raise ValueError("文档正文为空，没有可入库内容")

    records: list[tuple[str, str, dict[str, Any]]] = []
    for index, chunk in enumerate(chunks):
        chunk_id = f"{prefix}:{relative_path}:{index}"
        # content_hash 用于观测内容是否变化；upsert 策略下同 id 会整体覆盖
        records.append(
            (
                chunk_id,
                chunk["content"],
                {
                    "title": metadata["title"],
                    "category": metadata["category"],
                    "source_type": metadata["source_type"],
                    "role_direction": metadata["role_direction"],
                    "tags": metadata["tags"],
                    "relative_path": relative_path,
                    "section_title": chunk["title"],
                    "chunk_index": index,
                    "content_hash": hashlib.sha256(
                        chunk["content"].encode("utf-8")
                    ).hexdigest(),
                },
            )
        )
    return records


def _write_chunks(
    collection: Any,
    records: list[tuple[str, str, dict[str, Any]]],
    *,
    strategy: str,
    summary: IngestSummary,
) -> None:
    """按策略写入切片：upsert 覆盖更新，skip 先查询已存在 id 再只追加新增。"""
    if strategy == "skip":
        existing = _existing_ids(collection, [record[0] for record in records])
        to_write = [record for record in records if record[0] not in existing]
        summary.skipped_chunks += len(records) - len(to_write)
    else:
        to_write = records

    for start in range(0, len(to_write), _BATCH_SIZE):
        batch = to_write[start : start + _BATCH_SIZE]
        ids = [record[0] for record in batch]
        documents = [record[1] for record in batch]
        metadatas = [record[2] for record in batch]
        if strategy == "skip":
            collection.add(ids=ids, documents=documents, metadatas=metadatas)
        else:
            collection.upsert(ids=ids, documents=documents, metadatas=metadatas)
    summary.ingested_chunks += len(to_write)


def _existing_ids(collection: Any, chunk_ids: list[str]) -> set[str]:
    """分批查询，返回已存在的 chunk id 集合（Chroma 对缺失 id 不报错）。"""
    existing: set[str] = set()
    for start in range(0, len(chunk_ids), _BATCH_SIZE):
        batch = chunk_ids[start : start + _BATCH_SIZE]
        result = collection.get(ids=batch)
        existing.update(result.get("ids") or [])
    return existing


def _prune_stale(
    collection: Any,
    prefix: str,
    *,
    existing_paths: set[str],
    valid_ids: set[str],
    valid_paths: set[str],
) -> int:
    """清理 collection 中属于本前缀的失效切片。

    判定规则按优先级：
    1. 源文件已不存在（relative_path 不在盘上）→ 失效，清理；
    2. 源文件仍在但本次未通过校验（不在 valid_paths）→ 保留历史切片，
       避免一次临时的 frontmatter 错误导致整库误删；
    3. 源文件本次校验通过（在 valid_paths）→ 只保留本次生成的切片，
       chunk id 不在 valid_ids 中的（内容缩短/章节合并导致的索引越界孤儿）视为失效。
    """
    result = collection.get(include=["metadatas"])
    stale_ids: list[str] = []
    for chunk_id, metadata in zip(
        result.get("ids") or [], result.get("metadatas") or []
    ):
        if not chunk_id.startswith(f"{prefix}:"):
            continue
        # 缺少 relative_path 元信息的历史切片无法归类，视为失效避免永久残留
        relative_path = (metadata or {}).get("relative_path")
        if relative_path is None or relative_path not in existing_paths:
            stale_ids.append(chunk_id)
        elif relative_path in valid_paths and chunk_id not in valid_ids:
            stale_ids.append(chunk_id)
    for start in range(0, len(stale_ids), _BATCH_SIZE):
        collection.delete(ids=stale_ids[start : start + _BATCH_SIZE])
    return len(stale_ids)


def _existing_relative_paths(root: Path, source_type: str) -> set[str]:
    """返回该来源类型目录下当前仍存在的 Markdown 相对路径（排除 README）。

    直接从文件系统扫描，作为 prune 清理的依据：只要文件还在，即使本次
    frontmatter 校验失败，其历史切片也应保留。
    """
    directory = root / SOURCE_DIRECTORIES[source_type]
    if not directory.is_dir():
        return set()
    return {
        markdown_file.relative_to(root).as_posix()
        for markdown_file in directory.rglob("*.md")
        if markdown_file.name not in IGNORED_MARKDOWN_FILES
    }


def _collection_name_for(settings: Settings, source_type: str) -> str:
    if source_type == "KNOWLEDGE_BASE":
        return settings.chroma_knowledge_collection_name
    if source_type == "EXPERIENCE_CASE":
        return settings.chroma_experience_collection_name
    raise ValueError(f"未知材料来源类型：{source_type}")


def _collection_factory(settings: Settings, source_type: str) -> Any:
    if source_type == "KNOWLEDGE_BASE":
        return get_knowledge_collection(settings)
    if source_type == "EXPERIENCE_CASE":
        return get_experience_collection(settings)
    raise ValueError(f"未知材料来源类型：{source_type}")


def _split_sections(body: str) -> list[tuple[str | None, str]]:
    """把正文按标题行拆成 (标题, 内容) 片段，标题前的内容归入无标题片段。"""
    sections: list[tuple[str | None, list[str]]] = []
    current_title: str | None = None
    current_lines: list[str] = []

    for line in body.splitlines():
        match = _HEADING_RE.match(line)
        if match:
            if current_lines:
                sections.append((current_title, current_lines))
            current_title = match.group(2)
            current_lines = [line]
        else:
            current_lines.append(line)
    if current_lines:
        sections.append((current_title, current_lines))

    return [
        (title, "\n".join(lines).strip())
        for title, lines in sections
        if "\n".join(lines).strip()
    ]


def _split_section(text: str, *, max_chars: int, overlap: int) -> list[str]:
    """超长小节优先按空行段落聚合，单段仍超长时退化为字符切片。"""
    if len(text) <= max_chars:
        return [text]

    pieces: list[str] = []
    buffer: list[str] = []
    buffer_length = 0
    for paragraph in re.split(r"\n\s*\n", text):
        stripped = paragraph.strip()
        if not stripped:
            continue
        if len(stripped) > max_chars:
            # 单段超长：先清空缓冲区，再对该段按字符切片
            if buffer:
                pieces.append("\n\n".join(buffer))
                buffer, buffer_length = [], 0
            pieces.extend(_split_by_chars(stripped, max_chars=max_chars, overlap=overlap))
            continue
        if buffer and buffer_length + len(stripped) + 2 > max_chars:
            pieces.append("\n\n".join(buffer))
            buffer, buffer_length = [], 0
        buffer.append(stripped)
        buffer_length += len(stripped) + 2
    if buffer:
        pieces.append("\n\n".join(buffer))
    return pieces


def _split_by_chars(text: str, *, max_chars: int, overlap: int) -> list[str]:
    """按字符切片；overlap 收敛到 max_chars-1，避免重叠过大导致死循环。"""
    size = max(1, int(max_chars))
    effective_overlap = min(max(0, int(overlap)), size - 1)
    pieces: list[str] = []
    start = 0
    while start < len(text):
        end = min(len(text), start + size)
        piece = text[start:end].strip()
        if piece:
            pieces.append(piece)
        if end >= len(text):
            break
        start = end - effective_overlap
    return pieces


def _clean_heading(text: str) -> str:
    """去掉标题中的 Markdown 装饰符号（加粗/斜体/行内代码/井号），保留可读文本。"""
    return _MARKDOWN_EMPHASIS_RE.sub("", text).strip()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="将知识库 Markdown 离线写入 Chroma（八股与面经分集合存储）"
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "knowledge",
        help="knowledge 根目录，默认指向仓库根目录下的 knowledge",
    )
    parser.add_argument(
        "--strategy",
        choices=("upsert", "skip"),
        default="upsert",
        help="重复内容处理：upsert=覆盖更新（默认），skip=跳过已存在内容",
    )
    parser.add_argument(
        "--prune",
        action="store_true",
        help="入库后清理当前目录已不存在的失效切片（仅对本次涉及的 collection 生效）",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只统计待入库内容，不连接 Chroma、不写入任何数据",
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=1500,
        help="单个切片的字符上限，超长小节会二次切分（默认 1500）",
    )
    parser.add_argument(
        "--chunk-overlap",
        type=int,
        default=120,
        help="超长内容按字符切片时的重叠字符数（默认 120）",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="以 JSON 格式输出汇总结果，便于其他脚本消费",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")

    try:
        summary = ingest_knowledge_root(
            args.root,
            strategy=args.strategy,
            prune=args.prune,
            dry_run=args.dry_run,
            max_chars=args.chunk_size,
            overlap=args.chunk_overlap,
        )
    except Exception:
        # 文档级别的业务错误已收集进 summary.errors，能冒泡到这里的通常是
        # Chroma 连接/集合操作类依赖异常（chromadb 会把连接失败包装成空消息
        # 的 ValueError，无法按类型精确识别）。log.exception 保留完整 traceback，
        # 同时给出可操作的运维提示，避免直接抛出不直观的堆栈。
        log.exception("知识库入库失败")
        print(
            "ERROR 入库失败：请确认 Docker Chroma server 已启动且端口可访问"
            "（docker compose -f smartview-infra/docker-compose.yml up -d），"
            "并检查 QWEN_EMBEDDING_API_KEY 是否已配置。",
            file=sys.stderr,
        )
        return 2

    payload = {
        "root": str(args.root),
        "strategy": args.strategy,
        "prune": args.prune,
        "dryRun": args.dry_run,
        "scanned": summary.scanned,
        "validDocuments": summary.valid_documents,
        "invalidDocuments": summary.invalid_documents,
        "ingestedChunks": summary.ingested_chunks,
        "skippedChunks": summary.skipped_chunks,
        "prunedChunks": summary.pruned_chunks,
        "collectionCounts": summary.collection_counts,
        "errors": summary.errors,
    }
    if args.json:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    else:
        print(
            f"识别 {summary.scanned} 个 Markdown：有效 {summary.valid_documents}，"
            f"无效 {summary.invalid_documents}"
        )
        print(
            f"本次写入切片 {summary.ingested_chunks}，跳过 {summary.skipped_chunks}，"
            f"清理失效 {summary.pruned_chunks}"
        )
        for name, count in summary.collection_counts.items():
            print(f"collection {name} 当前条数：{count}")
        for error in summary.errors:
            print(f"ERROR {error}")

    # 与 inspect_knowledge_metadata.py 保持一致：存在元信息/数据错误时返回非 0
    return 1 if summary.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
