"""候选池组装节点。

对逐目标生成的原始候选做：历史主题去重、候选内去重、数量封顶，并补全元数据。
封顶规则（interview-policy.md 3.1）：预生成池 ≤ 4 道，追问池 ≤ 2 道。
"""

from __future__ import annotations

import logging
from typing import Any

from app.schemas.interview import CandidatePoolItem

log = logging.getLogger(__name__)

_MAX_PRE_GENERATED = 4
_MAX_FOLLOW_UP = 2


def build_candidate_pool(state: dict[str, Any]) -> dict[str, Any]:
    """组装候选池并写回 result 键（候选 dict 列表）。"""
    raw = state.get("raw_candidates") or []
    seen_topics = {str(t).strip() for t in state.get("history_topics") or [] if str(t).strip()}
    cap = _MAX_FOLLOW_UP if state.get("pool_type") == "FOLLOW_UP" else _MAX_PRE_GENERATED

    items: list[CandidatePoolItem] = []
    seen: set[tuple[str, str]] = set()
    current_topic = str(state.get("current_topic") or "").strip()
    for candidate in raw:
        question_text = str(candidate.get("questionText") or "").strip()
        topic = str(candidate.get("topic") or "").strip()
        candidate_type = str(candidate.get("candidateType") or "")
        if not question_text or not topic:
            continue
        # 与已问主题去重，避免重复出题。追问候选允许复用"当前主题"（追问本就围绕
        # 当前题展开），但若 LLM 把追问主题漂移到其他已问主题，仍按已问主题去重丢弃。
        if candidate_type == "FOLLOW_UP":
            if topic in seen_topics and topic != current_topic:
                continue
        elif topic in seen_topics:
            continue
        key = (topic, question_text)
        if key in seen:
            continue
        seen.add(key)
        items.append(
            CandidatePoolItem(
                questionText=question_text,
                topic=topic,
                stage=str(candidate.get("stage") or ""),
                candidateType=candidate.get("candidateType") or "SAME_STAGE_SWITCH",
                sourceType=candidate.get("sourceType"),
                expectedPoints=list(candidate.get("expectedPoints") or []),
                targetPoint=candidate.get("targetPoint"),
                reason=candidate.get("reason"),
            )
        )

    result = [item.model_dump(exclude_none=True) for item in items[:cap]]
    log.info(
        "候选池组装完成 session_id=%s pool_type=%s count=%s",
        state.get("session_id"),
        state.get("pool_type"),
        len(result),
    )
    return {"result": {"candidates": result}}
