"""扫描知识材料 Markdown 并输出元信息识别结果。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

try:
    from scripts.knowledge_metadata import scan_knowledge_root
except ModuleNotFoundError:
    # 直接执行 scripts/inspect_knowledge_metadata.py 时脚本目录不在包路径中，
    # 回退到同目录模块导入；python -m 方式则走上面的包导入。
    from knowledge_metadata import scan_knowledge_root


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="识别知识材料 Markdown 元信息")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2] / "knowledge",
        help="knowledge 根目录，默认指向仓库根目录下的 knowledge",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="以 JSON 格式输出，便于其他入库脚本继续消费",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    scan_result = scan_knowledge_root(args.root)
    valid_count = sum(1 for document in scan_result.documents if not document["errors"])

    if args.json:
        print(
            json.dumps(
                {
                    "root": str(args.root),
                    "documents": scan_result.documents,
                    "errors": scan_result.errors,
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        for document in scan_result.documents:
            status = "OK" if not document["errors"] else "ERROR"
            metadata = document["metadata"]
            print(
                f"{status} {document['relativePath']} | "
                f"{metadata.get('title', '')} | "
                f"{metadata.get('category', '')} | "
                f"{metadata.get('role_direction', '')}"
            )
        for error in scan_result.errors:
            print(f"ERROR {error}")
        print(
            f"共识别 {len(scan_result.documents)} 个 Markdown 文件，"
            f"其中 {valid_count} 个元信息有效。"
        )

    return 1 if scan_result.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
