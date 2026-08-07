"""候选问题 LLM 生成服务。

根据生成目标（stage/topic/candidateType/basis）调用 DeepSeek 生成单道候选问题，
并归一化输出字段（topic 缺失回退目标主题、来源类型非法回退知识库）。

候选池不做逐候选知识检索（缓存/兜底定位，检索代价高）；来源类型由目标阶段推断。
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.config import Settings
from app.services.deepseek_client import call_deepseek_json

log = logging.getLogger(__name__)

# 按阶段推断来源类型：BASIC→知识库、PROJECT→简历项目、SCENARIO→面经案例
_STAGE_SOURCE = {
    "BASIC": "KNOWLEDGE_BASE",
    "PROJECT": "RESUME_PROJECT",
    "SCENARIO": "EXPERIENCE_CASE",
}


async def generate_one(
    state: dict[str, Any], target: dict[str, Any], settings: Settings
) -> dict[str, Any]:
    """基于单个生成目标生成一道候选问题，返回已归一化的候选 dict。

    target 字段：stage、topic、candidateType，追问目标另有 basisType/basis。
    """
    messages = _build_messages(state, target)
    payload = await call_deepseek_json(messages, settings, what="候选题")
    return _normalize(state, target, payload)


def _build_messages(
    state: dict[str, Any], target: dict[str, Any]
) -> list[dict[str, str]]:
    """构造候选题提示词；追问目标携带评估事实与依据，换题目标携带主题。"""
    candidate_type = target.get("candidateType")
    if candidate_type == "FOLLOW_UP":
        system = (
            "你是资深的技术面试官。请基于候选人上一题的回答与评估事实，生成一道追问。"
            "追问必须紧扣回答内容与给定的追问依据，不得重复原题。"
            "只输出 JSON 对象：questionText(题目正文)、topic(主题)、"
            "sourceType(来源：KNOWLEDGE_BASE|EXPERIENCE_CASE|RESUME_PROJECT|MIXED)、"
            "expectedPoints(期望要点数组)、targetPoint(目标考察点)、reason(生成原因)。"
            "全部使用中文。"
        )
        facts = state.get("evaluation_facts") or {}
        user = (
            f"面试方向：{state.get('role_direction')}\n"
            f"当前题目：{facts.get('questionText') or ''}\n"
            f"用户回答：{facts.get('answerText') or ''}\n"
            f"得分：{facts.get('score')}，等级：{facts.get('level') or ''}\n"
            f"命中要点：{'、'.join(facts.get('matchedPoints') or [])}\n"
            f"缺失要点：{'、'.join(facts.get('missingPoints') or [])}\n"
            f"风险点：{'、'.join(str(r) for r in facts.get('riskPoints') or [])}\n"
            f"追问依据：{target.get('basis') or ''}\n"
            f"追问主题：{target.get('topic') or ''}"
        )
    else:
        system = (
            "你是资深的技术面试官。请为模拟面试生成一道候选问题，仅作备选，不决定下一步。"
            "只输出 JSON 对象：questionText(题目正文)、topic(主题)、"
            "sourceType(来源：KNOWLEDGE_BASE|EXPERIENCE_CASE|RESUME_PROJECT|MIXED)、"
            "expectedPoints(期望要点数组)、targetPoint(目标考察点)、reason(生成原因)。"
            "题目要具体、可作答，不要空洞；全部使用中文。"
        )
        history = state.get("history_topics") or []
        user = (
            f"面试方向：{state.get('role_direction')}\n"
            f"阶段：{target.get('stage')}\n"
            f"候选类型：{target.get('candidateType')}\n"
            f"主题：{target.get('topic')}\n"
            f"已问过的主题（避免重复）：{'、'.join(history) or '（无）'}"
        )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def _normalize(
    state: dict[str, Any], target: dict[str, Any], payload: dict[str, Any]
) -> dict[str, Any]:
    """归一化 LLM 输出：缺失字段回退，非法来源归一，保证候选字段可枚举。"""
    topic = str(payload.get("topic") or "").strip()
    if not topic:
        topic = str(target.get("topic") or "").strip()
    source = str(payload.get("sourceType") or "").strip()
    if source not in ("KNOWLEDGE_BASE", "EXPERIENCE_CASE", "RESUME_PROJECT", "MIXED"):
        # 来源缺失/非法时按目标阶段推断，保证溯源字段可枚举
        source = _STAGE_SOURCE.get(target.get("stage"), "KNOWLEDGE_BASE")
    target_point = str(payload.get("targetPoint") or "").strip()
    if not target_point:
        target_point = topic
    reason = str(payload.get("reason") or "").strip()
    if not reason and target.get("candidateType") == "FOLLOW_UP":
        reason = _default_reason(target)
    return {
        "questionText": str(payload.get("questionText") or "").strip(),
        "topic": topic,
        "stage": target.get("stage"),
        "candidateType": target.get("candidateType"),
        "sourceType": source,
        "expectedPoints": [
            str(point) for point in (payload.get("expectedPoints") or []) if str(point).strip()
        ],
        "targetPoint": target_point or None,
        "reason": reason or None,
    }


def _default_reason(target: dict[str, Any]) -> str:
    """追问目标缺失 reason 时的兜底描述。"""
    basis_type = target.get("basisType")
    if basis_type == "risk":
        return "针对回答中的风险点澄清"
    if basis_type == "deep":
        return "针对回答亮点深入追问"
    return "针对回答缺失要点补充提问"
