"""回答评估图节点：调用评估服务产出评估事实。

通过模块引用调用 answer_evaluator，便于测试 monkeypatch 服务函数。
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.config import Settings
from app.services import answer_evaluator

log = logging.getLogger(__name__)


async def evaluate_answer(state: dict[str, Any], settings: Settings) -> dict[str, Any]:
    """执行评估并写回 evaluation_facts 键。"""
    facts = await answer_evaluator.evaluate_answer(
        question_text=state.get("question_text") or "",
        answer_text=state.get("answer_text") or "",
        expected_points=state.get("expected_points") or [],
        role_direction=state.get("role_direction") or "",
        settings=settings,
    )
    log.info(
        "回答评估完成 session_id=%s score=%s level=%s",
        state.get("session_id"), facts.get("score"), facts.get("level"),
    )
    return {"evaluation_facts": facts}
