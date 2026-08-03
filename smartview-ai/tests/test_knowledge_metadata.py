import json
import os
import subprocess
import sys
from pathlib import Path

from scripts.knowledge_metadata import (
    parse_frontmatter,
    scan_knowledge_root,
    validate_document,
)


PROJECT_ROOT = Path(__file__).resolve().parents[2]
INSPECT_SCRIPT = PROJECT_ROOT / "smartview-ai" / "scripts" / "inspect_knowledge_metadata.py"


def test_parse_frontmatter_supports_list_tags() -> None:
    text = """---
title: Java 并发：线程池
category: Java 并发
source_type: KNOWLEDGE_BASE
role_direction: JAVA_BACKEND
tags:
  - 线程池
  - 并发
---
正文
"""
    metadata, errors = parse_frontmatter(text)

    assert errors == []
    assert metadata["title"] == "Java 并发：线程池"
    assert metadata["tags"] == ["线程池", "并发"]


def test_parse_frontmatter_supports_inline_tags() -> None:
    text = """---
title: JVM 内存
category: JVM
source_type: KNOWLEDGE_BASE
role_direction: JAVA_BACKEND
tags: [JVM, 内存]
---
"""
    metadata, errors = parse_frontmatter(text)

    assert errors == []
    assert metadata["tags"] == ["JVM", "内存"]


def test_parse_frontmatter_supports_yaml_comments_and_quoted_commas() -> None:
    text = """---
title: Agent
category: Agent
source_type: KNOWLEDGE_BASE
role_direction: AGENT_DEVELOPMENT
tags: ["Java, 并发", Agent] # 注释
---
"""

    metadata, errors = parse_frontmatter(text)

    assert errors == []
    assert metadata["tags"] == ["Java, 并发", "Agent"]


def test_parse_frontmatter_reports_missing_closing_delimiter() -> None:
    metadata, errors = parse_frontmatter(
        "---\ntitle: 缺少结束分隔符\n"
    )

    assert metadata == {}
    assert any("结束分隔符" in error for error in errors)


def test_validate_document_checks_source_type_directory() -> None:
    text = """---
title: 错误目录
category: Agent
source_type: KNOWLEDGE_BASE
role_direction: AGENT_DEVELOPMENT
tags:
  - Agent
---
"""
    metadata, parse_errors = parse_frontmatter(text)
    _, validation_errors = validate_document(
        metadata,
        Path("interview_experience_cases/wrong.md"),
    )

    assert parse_errors == []
    assert any("应放在" in error for error in validation_errors)


def test_validate_document_normalizes_comma_separated_tags() -> None:
    metadata = {
        "title": "Java 并发",
        "category": "Java 并发",
        "source_type": "KNOWLEDGE_BASE",
        "role_direction": "JAVA_BACKEND",
        "tags": "Java, 并发",
    }

    normalized, errors = validate_document(metadata, Path("interview_knowledge_base/x.md"))

    assert errors == []
    assert normalized["tags"] == ["Java", "并发"]


def test_validate_document_rejects_empty_or_tail_comma_tags() -> None:
    base = {
        "title": "测试",
        "category": "Agent",
        "source_type": "KNOWLEDGE_BASE",
        "role_direction": "AGENT_DEVELOPMENT",
    }

    _, empty_list_errors = validate_document(
        {**base, "tags": []},
        Path("interview_knowledge_base/x.md"),
    )
    _, tail_comma_errors = validate_document(
        {**base, "tags": "Agent,"},
        Path("interview_knowledge_base/x.md"),
    )
    _, empty_item_errors = validate_document(
        {**base, "tags": ["Agent", " "]},
        Path("interview_knowledge_base/x.md"),
    )

    assert any("不能为空" in error for error in empty_list_errors)
    assert any("空标签或尾逗号" in error for error in tail_comma_errors)
    assert any("空标签" in error for error in empty_item_errors)


def test_parse_frontmatter_reports_missing_opening_delimiter() -> None:
    metadata, errors = parse_frontmatter("title: 缺少分隔符\n")

    assert metadata == {}
    assert any("---" in error for error in errors)


def test_validate_document_rejects_invalid_enums() -> None:
    metadata = {
        "title": "测试",
        "category": "Agent",
        "source_type": "UNKNOWN",
        "role_direction": "UNKNOWN",
        "tags": ["Agent"],
    }

    _, errors = validate_document(metadata, Path("interview_knowledge_base/x.md"))

    assert any("source_type 必须是" in error for error in errors)
    assert any("role_direction 必须是" in error for error in errors)


def test_validate_document_reports_missing_enums_once() -> None:
    metadata = {
        "title": "测试",
        "category": "Agent",
        "tags": ["Agent"],
    }

    _, errors = validate_document(metadata, Path("interview_knowledge_base/x.md"))

    source_type_errors = [error for error in errors if "source_type" in error]
    role_direction_errors = [error for error in errors if "role_direction" in error]
    assert source_type_errors == ["缺少必填字段 source_type"]
    assert role_direction_errors == ["缺少必填字段 role_direction"]


def test_parse_frontmatter_reports_duplicate_top_level_key() -> None:
    text = """---
title: 第一次
title: 第二次
category: Agent
source_type: KNOWLEDGE_BASE
role_direction: AGENT_DEVELOPMENT
tags:
  - Agent
---
"""

    metadata, errors = parse_frontmatter(text)

    assert metadata == {}
    assert any("重复定义" in error for error in errors)


def test_parse_frontmatter_ignores_nested_indented_keys_in_duplicate_check() -> None:
    """嵌套结构内部的缩进键不应被误判为顶层重复键。"""
    text = """---
title: 测试
category: Agent
source_type: KNOWLEDGE_BASE
role_direction: AGENT_DEVELOPMENT
tags:
  - Agent
details:
  title: 子字段
  url: http://example.com/path:with
---
"""
    metadata, errors = parse_frontmatter(text)

    assert errors == []
    assert metadata["title"] == "测试"
    assert metadata["details"]["title"] == "子字段"


def test_parse_frontmatter_handles_bom_and_crlf() -> None:
    text = "\ufeff---\r\ntitle: BOM 与 CRLF\r\ncategory: Java 并发\r\n" \
        "source_type: KNOWLEDGE_BASE\r\nrole_direction: JAVA_BACKEND\r\n" \
        "tags: [BOM, CRLF]\r\n---\r\n正文\r\n"

    metadata, errors = parse_frontmatter(text)

    assert errors == []
    assert metadata["title"] == "BOM 与 CRLF"
    assert metadata["tags"] == ["BOM", "CRLF"]


def test_scan_real_knowledge_root_has_valid_metadata() -> None:
    scan_result = scan_knowledge_root(PROJECT_ROOT / "knowledge")
    # 真实材料文件名包含中文且内容可能变动，这里只断言结构而非具体文件名，
    # 避免材料更新导致测试与入库脚本脱节。
    assert scan_result.documents, "knowledge 根目录下应存在可识别的材料"
    assert scan_result.errors == []
    assert all(not document["errors"] for document in scan_result.documents)

    # 八股与面经两类来源都必须有真实材料，且落在各自规定的目录下
    relative_paths = [document["relativePath"] for document in scan_result.documents]
    assert any(
        path.startswith("interview_knowledge_base/") for path in relative_paths
    )
    assert any(
        path.startswith("interview_experience_cases/") for path in relative_paths
    )
    assert all(
        document["metadata"]["source_type"] in {"KNOWLEDGE_BASE", "EXPERIENCE_CASE"}
        for document in scan_result.documents
    )


def test_cli_outputs_json_for_valid_documents(tmp_path: Path) -> None:
    knowledge_dir = tmp_path / "interview_knowledge_base"
    knowledge_dir.mkdir()
    (tmp_path / "interview_experience_cases").mkdir()
    (knowledge_dir / "sample.md").write_text(
        """---
title: CLI 样例
category: Agent
source_type: KNOWLEDGE_BASE
role_direction: AGENT_DEVELOPMENT
tags:
  - Agent
---
""",
        encoding="utf-8",
    )

    result = subprocess.run(
        [
            sys.executable,
            str(INSPECT_SCRIPT),
            "--root",
            str(tmp_path),
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
    assert payload["documents"][0]["metadata"]["title"] == "CLI 样例"
    assert payload["errors"] == []


def test_cli_returns_nonzero_for_invalid_document(tmp_path: Path) -> None:
    knowledge_dir = tmp_path / "interview_knowledge_base"
    knowledge_dir.mkdir()
    (tmp_path / "interview_experience_cases").mkdir()
    (knowledge_dir / "bad.md").write_text(
        """---
title: 非法样例
category: Agent
source_type: UNKNOWN
role_direction: AGENT_DEVELOPMENT
tags:
  - Agent
---
""",
        encoding="utf-8",
    )

    result = subprocess.run(
        [
            sys.executable,
            str(INSPECT_SCRIPT),
            "--root",
            str(tmp_path),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
        check=False,
    )

    assert result.returncode == 1
    assert "source_type 必须是" in result.stdout
