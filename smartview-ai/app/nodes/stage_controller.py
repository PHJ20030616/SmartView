"""候选池生成目标计算节点（确定性，无 LLM 调用）。

职责边界（docs/interview-policy.md 3.2/3.3）：
- PRE_GENERATED：同阶段换题（覆盖当前阶段未覆盖的 required_topics，最多 2 道）
  + 下一阶段入口（下一阶段 typical 开场题，最多 2 道）；
- FOLLOW_UP：受深度门控（current_topic_follow_up_count >= max_follow_up_depth 则 0 道）
  与得分门控（<40→0；40-70→1 缺口；>70 有亮点→1 深度）+ 风险追问（riskPoints 非空→1），
  合计最多 2 道。

本节点只产出"生成目标"，不直接决定下一步；最终动作由 Spring StagePolicyEngine 决定。
"""

from __future__ import annotations

import logging
from typing import Any

log = logging.getLogger(__name__)

# 阶段推进顺序：BASIC → PROJECT → SCENARIO，最后阶段无下一阶段入口
_STAGE_ORDER = ["BASIC", "PROJECT", "SCENARIO"]

# 各类型候选数量上限（interview-policy.md 3.1）
_MAX_SAME_STAGE_SWITCH = 2
_MAX_NEXT_STAGE_ENTRY = 2
_MAX_FOLLOW_UP = 2


def compute_generation_targets(state: dict[str, Any]) -> dict[str, Any]:
    """根据 poolType 计算候选池生成目标，写回 state 的 generation_targets 键。

    返回 dict 供 LangGraph 节点更新 state（只写自己负责的键）。
    """
    pool_type = state.get("pool_type")
    if pool_type == "FOLLOW_UP":
        targets = _follow_up_targets(state)
    else:
        targets = _pre_generated_targets(state)
    log.info(
        "候选池目标计算完成 session_id=%s pool_type=%s targets=%s",
        state.get("session_id"),
        pool_type,
        len(targets),
    )
    return {"generation_targets": targets}


# ==================== 预生成候选池 ====================

def _pre_generated_targets(state: dict[str, Any]) -> list[dict[str, Any]]:
    """预生成池：同阶段换题目标 + 下一阶段入口目标。"""
    current_stage = state.get("current_stage")
    targets: list[dict[str, Any]] = []

    # 同阶段换题：优先未覆盖主题，全部覆盖时回退为 required_topics 剔除当前主题
    plan_stage = _plan_stage(state, current_stage)
    if plan_stage:
        required = [t for t in plan_stage.get("required_topics") or [] if t]
        coverage = _coverage_for(state.get("stage_coverage"), current_stage)
        covered = set(coverage.get("covered_topics") or [])
        missing = [t for t in coverage.get("missing_topics") or [] if t] or [
            t for t in required if t not in covered
        ]
        if not missing:
            current_topic = state.get("current_topic")
            missing = [t for t in required if t != current_topic]
        for topic in missing[:_MAX_SAME_STAGE_SWITCH]:
            targets.append(
                {
                    "stage": current_stage,
                    "topic": topic,
                    "candidateType": "SAME_STAGE_SWITCH",
                }
            )

    # 下一阶段入口：最后阶段（SCENARIO）无下一阶段
    next_stage = _next_stage(current_stage)
    if next_stage:
        next_plan = _plan_stage(state, next_stage)
        topics = [t for t in (next_plan or {}).get("required_topics") or [] if t]
        for topic in topics[:_MAX_NEXT_STAGE_ENTRY]:
            targets.append(
                {
                    "stage": next_stage,
                    "topic": topic,
                    "candidateType": "NEXT_STAGE_ENTRY",
                }
            )
    return targets


# ==================== 追问候选池 ====================

def _follow_up_targets(state: dict[str, Any]) -> list[dict[str, Any]]:
    """追问池：深度门控 + 得分门控 + 风险追问，最多 2 道（interview-policy.md 3.3）。"""
    current_stage = state.get("current_stage")
    plan_stage = _plan_stage(state, current_stage)
    if plan_stage is None:
        return []
    max_depth = plan_stage.get("max_follow_up_depth") or 0
    coverage = _coverage_for(state.get("stage_coverage"), current_stage)
    follow_up_count = coverage.get("current_topic_follow_up_count") or 0
    # 深度上限：达到阶段计划最大追问深度时不再生成追问
    if follow_up_count >= max_depth:
        return []

    facts = state.get("evaluation_facts") or {}
    score = facts.get("score")
    if score is None or score < 40:
        return []

    topic = state.get("current_topic") or ""
    targets: list[dict[str, Any]] = []
    if score <= 70:
        # 得分中等：针对缺失要点补充提问
        missing = facts.get("missingPoints") or []
        targets.append(
            {
                "stage": current_stage,
                "topic": topic,
                "candidateType": "FOLLOW_UP",
                "basisType": "missing",
                "basis": missing[0] if missing else "补充回答中缺失的要点",
            }
        )
    else:
        # 得分高且有亮点：基于命中要点深度追问
        matched = facts.get("matchedPoints") or []
        targets.append(
            {
                "stage": current_stage,
                "topic": topic,
                "candidateType": "FOLLOW_UP",
                "basisType": "deep",
                "basis": matched[0] if matched else "深入挖掘回答中的亮点",
            }
        )

    # 存在风险点时追加一道澄清追问（0-1 道）
    risks = facts.get("riskPoints") or []
    if risks:
        first = risks[0]
        risk_desc = (
            first.get("description") if isinstance(first, dict) else str(first)
        )
        targets.append(
            {
                "stage": current_stage,
                "topic": topic,
                "candidateType": "FOLLOW_UP",
                "basisType": "risk",
                "basis": risk_desc or "对回答中的疑点澄清",
            }
        )
    return targets[:_MAX_FOLLOW_UP]


# ==================== 私有辅助 ====================

def _plan_stage(state: dict[str, Any], stage: str | None) -> dict[str, Any] | None:
    """从阶段计划按阶段名取计划项；缺失返回 None。"""
    if not stage:
        return None
    for item in (state.get("stage_plan") or {}).get("stages") or []:
        if item.get("stage") == stage:
            return item
    return None


def _coverage_for(coverage: dict[str, Any] | None, stage: str | None) -> dict[str, Any]:
    """取指定阶段覆盖度；缺失视为空覆盖（等价于全部主题未覆盖）。"""
    if not coverage or not stage:
        return {}
    item = coverage.get(stage)
    return item if isinstance(item, dict) else {}


def _next_stage(current_stage: str | None) -> str | None:
    """按阶段顺序取下一阶段；当前为最后阶段（SCENARIO）或未知时返回 None。"""
    if current_stage not in _STAGE_ORDER:
        return None
    index = _STAGE_ORDER.index(current_stage)
    return _STAGE_ORDER[index + 1] if index + 1 < len(_STAGE_ORDER) else None
