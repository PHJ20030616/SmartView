"""知识材料 Markdown 元信息解析与校验。"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

# 材料来源类型与知识根目录下的目录名一一对应，避免八股和面经混放。
SOURCE_TYPES = {"KNOWLEDGE_BASE", "EXPERIENCE_CASE"}
SOURCE_DIRECTORIES = {
    "KNOWLEDGE_BASE": "interview_knowledge_base",
    "EXPERIENCE_CASE": "interview_experience_cases",
}
ROLE_DIRECTIONS = {"JAVA_BACKEND", "AGENT_DEVELOPMENT"}
REQUIRED_FIELDS = ("title", "category", "source_type", "role_direction", "tags")
# README 只描述目录用途，不作为待入库材料。
IGNORED_MARKDOWN_FILES = {"README.md"}


@dataclass
class KnowledgeScan:
    """一次目录扫描的识别结果。"""

    documents: list[dict[str, Any]]
    errors: list[str]


def parse_frontmatter(text: str) -> tuple[dict[str, Any], list[str]]:
    """解析 Markdown 开头的 YAML frontmatter，返回元信息和解析错误。"""
    errors: list[str] = []
    lines = text.lstrip("\ufeff").splitlines()
    if not lines or lines[0].strip() != "---":
        return {}, ["缺少以 --- 开头的 frontmatter"]

    closing_index: int | None = None
    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            closing_index = index
            break
    if closing_index is None:
        return {}, ["frontmatter 缺少结束分隔符 ---"]

    frontmatter = "\n".join(lines[1:closing_index])
    # PyYAML 默认不会报重复键，而重复键会让后一个值静默覆盖前一个值，
    # 因此单独对顶层键做查重。只统计非缩进的顶层键；缩进键属于嵌套结构内部，
    # 块标量正文中形如“key: value”的行也位于缩进内，不会被误判为顶层重复。
    # 注意：flow 风格 {a: 1, a: 2} 的重复键仍会被 PyYAML 静默覆盖，此处不覆盖该场景。
    seen_keys: set[str] = set()
    for line in frontmatter.splitlines():
        if not line.strip():
            continue
        # 缩进行（嵌套键、块标量内容）以及注释、列表项不参与顶层键查重
        if line.startswith((" ", "\t")) or line.lstrip().startswith(("#", "-")):
            continue
        if ":" not in line:
            continue
        key = line.split(":", 1)[0].strip()
        if key in seen_keys:
            errors.append(f"字段 {key} 重复定义")
        seen_keys.add(key)
    if errors:
        return {}, errors

    try:
        metadata = yaml.safe_load(frontmatter)
    except yaml.YAMLError as exc:
        return {}, [f"frontmatter YAML 解析失败：{exc}"]
    if not isinstance(metadata, dict):
        return {}, ["frontmatter 必须是键值对"]
    return metadata, []


def extract_frontmatter_body(text: str) -> tuple[str, list[str]]:
    """提取 Markdown frontmatter 之后的正文，供入库脚本切片使用。

    返回 (正文, 错误)；frontmatter 缺失或未闭合时返回原文/空串并给出错误，
    与 parse_frontmatter 的错误口径保持一致，避免入库脚本重复实现分隔符查找。
    """
    lines = text.lstrip("\ufeff").splitlines()
    if not lines or lines[0].strip() != "---":
        return text, ["缺少以 --- 开头的 frontmatter"]

    for index in range(1, len(lines)):
        if lines[index].strip() == "---":
            return "\n".join(lines[index + 1 :]), []
    return "", ["frontmatter 缺少结束分隔符 ---"]


def validate_document(
    metadata: dict[str, Any],
    relative_path: Path,
) -> tuple[dict[str, Any], list[str]]:
    """校验元信息是否完整、取值是否合法，并返回规范化后的元信息。"""
    normalized = dict(metadata)
    errors: list[str] = []

    for field in REQUIRED_FIELDS:
        if field == "tags":
            normalized[field], tag_errors = _normalize_tags(normalized.get(field))
            errors.extend(tag_errors)
            continue
        if field in {"source_type", "role_direction"}:
            # 枚举字段单独校验，避免“缺失”和“取值非法”两段逻辑重复报错。
            continue
        value = normalized.get(field)
        if field in {"title", "category"} and (not isinstance(value, str) or not value.strip()):
            errors.append(f"缺少必填字段 {field}")
        elif value in (None, ""):
            errors.append(f"缺少必填字段 {field}")

    source_type = normalized.get("source_type")
    if source_type in (None, ""):
        errors.append("缺少必填字段 source_type")
    elif source_type not in SOURCE_TYPES:
        errors.append(f"source_type 必须是 {' 或 '.join(sorted(SOURCE_TYPES))}")
    else:
        expected_directory = SOURCE_DIRECTORIES[source_type]
        actual_directory = relative_path.parts[0] if relative_path.parts else ""
        if actual_directory != expected_directory:
            errors.append(
                f"source_type={source_type} 应放在 {expected_directory} 目录，当前目录是 {actual_directory}"
            )

    role_direction = normalized.get("role_direction")
    if role_direction in (None, ""):
        errors.append("缺少必填字段 role_direction")
    elif role_direction not in ROLE_DIRECTIONS:
        errors.append(f"role_direction 必须是 {' 或 '.join(sorted(ROLE_DIRECTIONS))}")

    return normalized, errors


def scan_knowledge_root(root: Path) -> KnowledgeScan:
    """扫描两个知识目录下的 Markdown，识别并校验元信息。"""
    root = Path(root)
    documents: list[dict[str, Any]] = []
    errors: list[str] = []

    for source_type, directory_name in SOURCE_DIRECTORIES.items():
        directory = root / directory_name
        if not directory.is_dir():
            errors.append(f"缺少知识目录：{directory}")
            continue

        for markdown_file in sorted(directory.rglob("*.md")):
            if markdown_file.name in IGNORED_MARKDOWN_FILES:
                continue

            relative_path = markdown_file.relative_to(root)
            try:
                text = markdown_file.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as exc:
                errors.append(f"读取失败：{relative_path}（{exc}）")
                continue

            metadata, parse_errors = parse_frontmatter(text)
            if parse_errors:
                relative_posix = relative_path.as_posix()
                errors.extend(f"{relative_posix}: {error}" for error in parse_errors)
                documents.append(
                    {
                        "path": str(markdown_file),
                        "relativePath": relative_posix,
                        "metadata": metadata,
                        "errors": parse_errors,
                    }
                )
                continue

            normalized_metadata, validation_errors = validate_document(
                metadata,
                relative_path,
            )
            relative_posix = relative_path.as_posix()
            errors.extend(f"{relative_posix}: {error}" for error in validation_errors)
            documents.append(
                {
                    "path": str(markdown_file),
                    "relativePath": relative_posix,
                    "metadata": normalized_metadata,
                    "errors": validation_errors,
                }
            )

    return KnowledgeScan(documents=documents, errors=errors)


def _normalize_tags(value: Any) -> tuple[list[str], list[str]]:
    """把 tags 统一为字符串列表，并拒绝空标签、尾逗号等无效写法。"""
    if value is None:
        return [], ["tags 不能为空"]
    if isinstance(value, list):
        tags = [str(tag).strip() for tag in value if tag is not None and str(tag).strip()]
        if not tags:
            return [], ["tags 不能为空"]
        if any(tag is None or not str(tag).strip() for tag in value):
            return tags, ["tags 不能包含空标签"]
        return tags, []
    if isinstance(value, str):
        parts = value.split(",")
        tags = [tag.strip() for tag in parts]
        if any(not tag for tag in tags):
            return [tag for tag in tags if tag], ["tags 不能包含空标签或尾逗号"]
        return tags, []
    return [], ["tags 必须是字符串列表或逗号分隔字符串"]
