"""候选问题 LLM 生成服务。

根据生成目标（stage/topic/candidateType/followUpKind）调用 DeepSeek 生成单道候选问题，
并归一化输出字段（topic 缺失回退目标主题、来源类型非法回退知识库）。

候选池不做逐候选知识检索（缓存/兜底定位，检索代价高）；来源类型由目标阶段推断。

追问候选只依据"题目 + 候选人回答 + 期望要点"，**不读评估事实**：追问生成与回答评估
并行执行，生成时评估结论尚不可用；实测（develop_plan/plan_1.3.md）该提示词产出的追问
与带评估事实版本盲评打平，且少一轮等待。
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

# 追问类型对应的出题角度（写入提示词，使两型候选明显不同）
_FOLLOW_UP_FOCUS = {
    "GAP": "优先针对回答中未展开、含糊或明显缺失的要点提出追问",
    "DEEP": "优先针对回答中讲得较清楚或较独特的点继续深挖",
}


async def generate_one(
    state: dict[str, Any], target: dict[str, Any], settings: Settings
) -> dict[str, Any]:
    """基于单个生成目标生成一道候选问题，返回已归一化的候选 dict。

    target 字段：stage、topic、candidateType，追问目标另有 followUpKind（GAP/DEEP）。
    """
    messages = _build_messages(state, target)
    payload = await call_deepseek_json(
        messages,
        settings,
        scene="question_generate",
        what="候选题",
        # 追问与普通候选题的提示词结构不同，用 prompt_key 区分后，
        # 指标变化才能归因到具体是哪一类生成目标。
        prompt_key=(
            "question_generate.follow_up"
            if target.get("candidateType") == "FOLLOW_UP"
            else "question_generate.candidate"
        ),
    )
    return _normalize(state, target, payload)


def _build_messages(
    state: dict[str, Any], target: dict[str, Any]
) -> list[dict[str, str]]:
    """构造候选题提示词；追问目标用题目/回答/期望要点 + 追问角度，换题目标携带主题。"""
    candidate_type = target.get("candidateType")
    if candidate_type == "FOLLOW_UP":
        system = (
            "你是资深的技术面试官。请基于候选人上一题的回答生成一道追问。"
            "追问必须紧扣回答内容，不得重复原题。"
            "只输出 JSON 对象：questionText(题目正文)、topic(主题)、"
            "sourceType(来源：KNOWLEDGE_BASE|EXPERIENCE_CASE|RESUME_PROJECT|MIXED)、"
            "expectedPoints(期望要点数组)、targetPoint(目标考察点)、reason(生成原因)。"
            "全部使用中文。"
        )
        # 追问角度：优先按生成目标标注的 GAP/DEEP 取角度；未标注（旧数据/新调用方遗漏）
        # 时用中性兜底角度，避免提示词出现空行
        focus = _FOLLOW_UP_FOCUS.get(
            str(target.get("followUpKind") or ""), "紧扣回答内容提出追问"
        )
        user = (
            f"面试方向：{state.get('role_direction')}\n"
            f"当前题目：{state.get('question_text') or ''}\n"
            f"用户回答：{state.get('answer_text') or ''}\n"
            f"题目期望要点：{'、'.join(state.get('expected_points') or []) or '（未提供）'}\n"
            f"追问角度：{focus}\n"
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
    raw_points = payload.get("expectedPoints")
    if isinstance(raw_points, str):
        # 模型偶发把数组返回成字符串：包裹为单元素列表，避免逐字符拆分
        raw_points = [raw_points]
    points = raw_points if isinstance(raw_points, list) else []
    # 追问类型由生成目标决定（不是模型输出）：决策侧按它选择使用哪一型追问。
    # 这里做一次显式校验：target 缺失或值非法时收敛为 None 并留下 warn —— 契约里
    # followUpKind 是可选的，缺失时决策侧只能软回退（取首个候选），"80 分喂补缺口型
    # 追问"这类偏差在链路上没有别的地方能发现，必须在此处可观测。
    follow_up_kind: str | None = None
    if target.get("candidateType") == "FOLLOW_UP":
        kind_raw = str(target.get("followUpKind") or "").strip().upper()
        if kind_raw in ("GAP", "DEEP"):
            follow_up_kind = kind_raw
        else:
            log.warning(
                "追问生成目标缺少合法的 followUpKind，候选将不带类型标注（决策侧软回退）"
                " session_id=%s target=%s",
                state.get("session_id"),
                target,
            )
    return {
        "questionText": str(payload.get("questionText") or "").strip(),
        "topic": topic,
        "stage": target.get("stage"),
        "candidateType": target.get("candidateType"),
        "followUpKind": follow_up_kind,
        "sourceType": source,
        "expectedPoints": [
            str(point) for point in points if str(point).strip()
        ],
        "targetPoint": target_point or None,
        "reason": reason or None,
    }


def _default_reason(target: dict[str, Any]) -> str:
    """追问目标缺失 reason 时的兜底描述（按追问类型给出可追溯的生成原因）。"""
    if target.get("followUpKind") == "DEEP":
        return "针对回答亮点深入追问"
    return "针对回答缺失或含糊的要点补充提问"
