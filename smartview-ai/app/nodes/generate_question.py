"""候选池逐目标生成节点。

遍历 stage_controller 产出的生成目标，为每个目标生成一道候选问题；
单目标 LLM 失败不阻断整体，失败目标被降级丢弃（候选池是尽力而为的缓存）。

生成目标之间彼此独立（提示词只用到当前目标与已问主题），因此并发执行：
串行时接口耗时随目标数线性增长（实测 4 个目标 28.8~34.7 秒，每次调用 6.8~10.9 秒），
并发后总耗时回落到单次调用量级。并发度由 LLM_MAX_CONCURRENCY 控制，
网关限流时把它改成 1 即退回原有串行行为。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.core.config import Settings
from app.services.question_generator import generate_one

log = logging.getLogger(__name__)


async def generate_questions(
    state: dict[str, Any], settings: Settings
) -> dict[str, Any]:
    """并发执行所有生成目标，返回 raw_candidates（含完整候选字段的 dict 列表）。

    返回顺序与 generation_targets 一致（asyncio.gather 保序）：
    后续组装节点按顺序封顶（预生成池 ≤ 4 道），顺序稳定才能保证同样的输入
    得到同样的候选池。
    """
    targets = state.get("generation_targets") or []
    if not targets:
        return {"raw_candidates": []}

    concurrency = max(1, int(settings.llm_max_concurrency))
    semaphore = asyncio.Semaphore(concurrency)

    async def _generate_with_limit(target: dict[str, Any]) -> dict[str, Any] | None:
        async with semaphore:
            try:
                return await generate_one(state, target, settings)
            except Exception as exc:  # noqa: BLE001 - 单目标失败降级，不阻断整体
                log.warning(
                    "候选题生成失败已降级 session_id=%s topic=%s error=%s",
                    state.get("session_id"),
                    target.get("topic"),
                    exc,
                )
                return None

    results = await asyncio.gather(
        *(_generate_with_limit(target) for target in targets)
    )
    return {"raw_candidates": [item for item in results if item is not None]}
