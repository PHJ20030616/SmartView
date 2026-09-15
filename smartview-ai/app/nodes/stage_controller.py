"""候选池生成目标计算节点（确定性，无 LLM 调用）。

职责边界（docs/interview-policy.md 3.2/3.3）：
- PRE_GENERATED：同阶段换题（覆盖当前阶段未覆盖的 required_topics，最多 2 道）
  + 下一阶段入口（下一阶段 typical 开场题，最多 2 道）；
- FOLLOW_UP：受深度门控（current_topic_follow_up_count >= max_follow_up_depth 则 0 道）
  与空答门控（空白/明确表示不会 → 0 道），否则产出"补缺口 GAP + 深挖 DEEP"两型目标各 1 道。

追问目标**不再读取评估事实**：追问候选与回答评估在 evaluate_answer_graph 中并行生成，
生成时拿不到得分。得分门控（<40 不追问、40-70 用 GAP、>70 用 DEEP）由 Spring
StagePolicyEngine 在决策时按 followUpKind 执行，从而把"生成什么"与"是否采用"解耦。

本节点只产出"生成目标"，不直接决定下一步；最终动作由 Spring StagePolicyEngine 决定。
"""

from __future__ import annotations

import logging
from typing import Any

from app.services.answer_evaluator import is_obviously_weak_answer

log = logging.getLogger(__name__)

# 阶段推进顺序：BASIC → PROJECT → SCENARIO，最后阶段无下一阶段入口
_STAGE_ORDER = ["BASIC", "PROJECT", "SCENARIO"]

# 各类型候选数量上限（interview-policy.md 3.1）；追问池上限由 build_candidate_pool 封顶
_MAX_SAME_STAGE_SWITCH = 2
_MAX_NEXT_STAGE_ENTRY = 2


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
    """追问池：深度门控 + 空答门控，产出补缺口（GAP）与深挖（DEEP）两型目标。

    与评估并行时拿不到得分，因此这里不按得分过滤（门控在 Spring 决策侧，见模块注释）：
    两型各生成一道，决策侧按得分择一使用——"生成什么"与"是否采用"由此解耦。

    返回顺序固定为 [GAP, DEEP]：候选池按顺序封顶且决策侧按类型择一，顺序稳定才能保证
    同样的输入产出同样的候选池。
    """
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
    # 空答/明确不会：没有可追问的内容，保持零 LLM 调用（与评估侧同一套判定）
    if is_obviously_weak_answer(state.get("answer_text") or ""):
        return []

    topic = state.get("current_topic") or ""
    return [
        {
            "stage": current_stage,
            "topic": topic,
            "candidateType": "FOLLOW_UP",
            "followUpKind": "GAP",
        },
        {
            "stage": current_stage,
            "topic": topic,
            "candidateType": "FOLLOW_UP",
            "followUpKind": "DEEP",
        },
    ]


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
