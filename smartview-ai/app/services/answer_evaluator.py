"""回答评估服务。

基于题目期望要点 + 问题正文 + 用户回答，直接调用 DeepSeek 评分（不做知识检索，
决策见 Task 5.4 设计）；识别"不会/不熟悉"等关键词直接给低分，不消耗 LLM 调用，
满足"回答不会时降级追问/切换主题"的验收标准。
"""

from __future__ import annotations

import logging
from typing import Any

from app.core.config import Settings
from app.services.deepseek_client import call_deepseek_json

log = logging.getLogger(__name__)

# 明确表示不会/不熟悉的回答：直接给低分（不调用 LLM）
_WEAK_KEYWORDS = ("不会", "不熟悉", "不知道", "没学过", "不清楚", "不了解", "答不上来")


def _is_weak_keyword(answer_text: str) -> bool:
    """判断回答是否明确表示不会/不熟悉，或完全空白未作答。

    仅对空白与显式否定关键词判弱；简短但肯定的回答（如"了解"）交给 LLM 评估，
    避免误杀自信回答导致提前触发 QUALITY_TOO_LOW 结束。
    """
    text = (answer_text or "").strip()
    if not text:
        return True
    return any(kw in text for kw in _WEAK_KEYWORDS)


async def evaluate_answer(
    *,
    question_text: str,
    answer_text: str,
    expected_points: list[str],
    role_direction: str,
    settings: Settings,
) -> dict[str, Any]:
    """评估回答并返回评估事实 dict。

    facts 键：score/level/matchedPoints/missingPoints/riskPoints，
    另回填 answerText/questionText 供追问候选生成提示词使用。
    """
    if _is_weak_keyword(answer_text):
        log.info("回答命中弱回答关键词，直接低分 question=%s", question_text[:40])
        return {
            "score": 15,
            "level": "WEAK",
            "matchedPoints": [],
            "missingPoints": expected_points or ["未能作答"],
            "riskPoints": [{"category": "BASIC_MASTERY", "description": "回答表示不熟悉或无法作答"}],
            "answerText": answer_text,
            "questionText": question_text,
        }
    messages = _build_messages(question_text, answer_text, expected_points, role_direction)
    payload = await call_deepseek_json(messages, settings, what="回答评估")
    facts = _normalize(payload, expected_points)
    facts["answerText"] = answer_text
    facts["questionText"] = question_text
    return facts


def _build_messages(
    question_text: str, answer_text: str, expected_points: list[str], role_direction: str
) -> list[dict[str, str]]:
    """构造评估提示词：对照期望要点打分。"""
    system = (
        "你是资深的技术面试官。请根据题目期望回答要点评估候选人回答。"
        "只输出 JSON 对象，字段必须严格包含："
        "score(0-100 整数)、level(GOOD|NORMAL|WEAK)、"
        "matchedPoints(命中的要点数组)、missingPoints(缺失的要点数组)、"
        "riskPoints(风险点数组，元素含 category 与 description)。"
        "打分依据：命中要点越多分越高；回答明显错误或空泛给低分。全部使用中文。"
    )
    user = (
        f"面试方向：{role_direction}\n"
        f"题目：{question_text}\n"
        f"期望回答要点：{'、'.join(expected_points) or '（未提供）'}\n"
        f"候选人回答：{answer_text}"
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def _normalize(payload: dict[str, Any], expected_points: list[str]) -> dict[str, Any]:
    """归一化 LLM 输出：字段缺失回退、得分越界收敛，保证可枚举。"""
    score = int(payload.get("score") or 0)
    score = max(0, min(100, score))
    level = str(payload.get("level") or "").strip().upper()
    if level not in ("GOOD", "NORMAL", "WEAK"):
        level = "NORMAL" if 40 <= score < 70 else ("GOOD" if score >= 70 else "WEAK")
    matched = [str(p) for p in (payload.get("matchedPoints") or []) if str(p).strip()]
    missing = [str(p) for p in (payload.get("missingPoints") or []) if str(p).strip()]
    risks = [r for r in (payload.get("riskPoints") or []) if isinstance(r, dict)]
    if not missing and expected_points:
        # 模型未给缺失点但期望要点未全部命中时，用未命中期望要点补齐
        matched_set = {m for m in matched}
        missing = [p for p in expected_points if p not in matched_set]
    return {
        "score": score,
        "level": level,
        "matchedPoints": matched,
        "missingPoints": missing,
        "riskPoints": risks,
    }
