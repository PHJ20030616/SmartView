"""候选池逐目标生成节点。

遍历 stage_controller 产出的生成目标，逐个调用 question_generator 生成候选问题；
单目标 LLM 失败不阻断整体，失败目标被降级丢弃（候选池是尽力而为的缓存）。
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.config import Settings
from app.services.question_generator import generate_one

log = logging.getLogger(__name__)


async def generate_questions(
    state: dict[str, Any], settings: Settings
) -> dict[str, Any]:
    """执行所有生成目标，返回 raw_candidates（含完整候选字段的 dict 列表）。"""
    targets = state.get("generation_targets") or []
    candidates: list[dict[str, Any]] = []
    for target in targets:
        try:
            candidates.append(await generate_one(state, target, settings))
        except Exception as exc:  # noqa: BLE001 - 单目标失败降级，不阻断整体
            log.warning(
                "候选题生成失败已降级 session_id=%s topic=%s error=%s",
                state.get("session_id"),
                target.get("topic"),
                exc,
            )
    return {"raw_candidates": candidates}
