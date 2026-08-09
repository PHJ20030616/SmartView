"""回答评估 LangGraph 状态机。

图流程：evaluate_answer → stage_controller → generate_questions → build_candidate_pool。
职责边界（docs/interview-policy.md 1.x）：只返回评估事实 + 追问候选（FOLLOW_UP 池），
不返回任何决策字段；最终动作由 Spring StagePolicyEngine 决定。
"""

from __future__ import annotations

import logging
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.nodes.build_candidate_pool import build_candidate_pool
from app.nodes.evaluate_answer import evaluate_answer
from app.nodes.generate_question import generate_questions
from app.nodes.stage_controller import compute_generation_targets
from app.schemas.interview import (
    CandidatePoolItem,
    EvaluateAnswerRequest,
    EvaluateAnswerResponse,
)

log = logging.getLogger(__name__)


class EvaluateAnswerState(TypedDict, total=False):
    """评估图状态（部分键：节点只更新自己负责的键）。"""

    session_id: str
    question_id: str
    role_direction: str
    question_text: str
    answer_text: str
    expected_points: list[str]
    current_stage: str
    current_topic: str | None
    question_count: int | None
    stage_plan: dict[str, Any]
    stage_coverage: dict[str, Any]
    pool_type: str
    history_topics: list[str]
    generation_targets: list[dict[str, Any]]
    raw_candidates: list[dict[str, Any]]
    evaluation_facts: dict[str, Any] | None
    result: dict[str, Any] | None


class EvaluateAnswerGraph:
    """回答评估状态机；settings 可注入以便测试。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._compiled = self._build()

    def _build(self) -> Any:
        """组装并编译 LangGraph 图（追问候选生成复用候选池图节点，DRY）。"""
        builder = StateGraph(EvaluateAnswerState)
        builder.add_node("evaluate_answer", self._evaluate_wrapper)
        builder.add_node("stage_controller", lambda state: compute_generation_targets(state))
        builder.add_node("generate_questions", self._generate_wrapper)
        builder.add_node("build_candidate_pool", lambda state: build_candidate_pool(state))
        builder.add_edge(START, "evaluate_answer")
        builder.add_edge("evaluate_answer", "stage_controller")
        builder.add_edge("stage_controller", "generate_questions")
        builder.add_edge("generate_questions", "build_candidate_pool")
        builder.add_edge("build_candidate_pool", END)
        return builder.compile()

    async def _evaluate_wrapper(self, state: EvaluateAnswerState) -> dict[str, Any]:
        return await evaluate_answer(state, self.settings)

    async def _generate_wrapper(self, state: EvaluateAnswerState) -> dict[str, Any]:
        return await generate_questions(state, self.settings)

    async def evaluate(self, request: EvaluateAnswerRequest) -> EvaluateAnswerResponse:
        """执行回答评估并返回契约响应（评估事实 + 追问候选）。"""
        initial: EvaluateAnswerState = {
            "session_id": request.sessionId,
            "question_id": request.questionId,
            "role_direction": request.roleDirection,
            "question_text": request.questionText,
            "answer_text": request.answerText,
            "expected_points": request.expectedPoints,
            "current_stage": request.sessionContext.currentStage or "",
            "current_topic": request.sessionContext.currentTopic,
            "question_count": request.sessionContext.questionCount,
            "stage_plan": request.stagePlan.model_dump(mode="json", exclude_none=True),
            "stage_coverage": request.sessionContext.stageCoverage or {},
            # 评估图只产追问候选；追问候选故意复用当前主题，history_topics 置空
            "pool_type": "FOLLOW_UP",
            "history_topics": [],
            "generation_targets": [],
            "raw_candidates": [],
            "evaluation_facts": None,
            "result": None,
        }
        try:
            final = await self._compiled.ainvoke(initial)
            facts = final.get("evaluation_facts")
            if not facts:
                raise AppError("回答评估结果为空", code="EMPTY_EVALUATION_RESULT")
            candidates = [
                CandidatePoolItem(**candidate)
                for candidate in (final.get("result") or {}).get("candidates") or []
            ]
            return EvaluateAnswerResponse(
                success=True,
                score=facts.get("score"),
                level=facts.get("level"),
                matchedPoints=facts.get("matchedPoints") or [],
                missingPoints=facts.get("missingPoints") or [],
                riskPoints=facts.get("riskPoints") or [],
                followUpCandidates=candidates,
            )
        except AppError as exc:
            return EvaluateAnswerResponse(success=False, errorMessage=exc.message)
        except Exception as exc:  # noqa: BLE001 - 对外隐藏内部堆栈
            log.exception(
                "回答评估图执行异常 session_id=%s question_id=%s",
                request.sessionId, request.questionId,
            )
            return EvaluateAnswerResponse(success=False, errorMessage=f"回答评估失败：{exc}")
