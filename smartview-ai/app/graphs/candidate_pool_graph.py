"""候选池生成 LangGraph 状态机。

图流程：stage_controller → generate_questions → build_candidate_pool。
职责边界（docs/interview-policy.md 1.x）：只返回候选问题，不返回任何决策字段；
最终动作由 Spring Boot 的 StagePolicyEngine 决定。

节点均为纯状态函数，便于单测注入替身；LLM 调用统一走 question_generator。
"""

from __future__ import annotations

import logging
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.nodes.build_candidate_pool import build_candidate_pool
from app.nodes.generate_question import generate_questions
from app.nodes.stage_controller import compute_generation_targets
from app.schemas.interview import (
    CandidatePoolItem,
    GenerateCandidatePoolRequest,
    GenerateCandidatePoolResponse,
)

log = logging.getLogger(__name__)


class CandidatePoolState(TypedDict, total=False):
    """候选池生成图的状态（部分键：节点只更新自己负责的键）。"""

    session_id: str
    question_id: str
    role_direction: str
    pool_type: str
    current_stage: str
    stage_plan: dict[str, Any]
    stage_coverage: dict[str, Any]
    current_topic: str | None
    question_count: int | None
    evaluation_facts: dict[str, Any] | None
    history_topics: list[str]
    generation_targets: list[dict[str, Any]]
    raw_candidates: list[dict[str, Any]]
    result: dict[str, Any] | None


class CandidatePoolGraph:
    """候选池生成状态机；settings 可注入以便测试。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._compiled = self._build()

    def _build(self) -> Any:
        """组装并编译 LangGraph 图。

        generate_questions 为 async 节点（LangGraph ainvoke 会 await 协程节点），
        用实例方法包装以携带 settings 依赖，便于测试注入。
        """
        builder = StateGraph(CandidatePoolState)
        builder.add_node("stage_controller", lambda state: compute_generation_targets(state))
        builder.add_node("generate_questions", self._generate_questions_wrapper)
        builder.add_node("build_candidate_pool", lambda state: build_candidate_pool(state))
        builder.add_edge(START, "stage_controller")
        builder.add_edge("stage_controller", "generate_questions")
        builder.add_edge("generate_questions", "build_candidate_pool")
        builder.add_edge("build_candidate_pool", END)
        return builder.compile()

    async def _generate_questions_wrapper(self, state: CandidatePoolState) -> dict[str, Any]:
        """async 节点包装：LangGraph 支持 async 节点，此处直接执行生成。"""
        return await generate_questions(state, self.settings)

    async def generate(
        self, request: GenerateCandidatePoolRequest
    ) -> GenerateCandidatePoolResponse:
        """执行候选池生成并返回契约响应。

        确定性业务错误与 LLM 可恢复错误统一映射为 success=false；
        未预期异常记日志并返回可读错误。
        """
        initial: CandidatePoolState = {
            "session_id": request.sessionId,
            "question_id": request.questionId,
            "role_direction": request.roleDirection,
            "pool_type": request.poolType,
            "current_stage": request.currentStage or "",
            "stage_plan": request.stagePlan.model_dump(mode="json", exclude_none=True),
            "stage_coverage": request.stageCoverage,
            "current_topic": request.sessionContext.currentTopic,
            "question_count": request.sessionContext.questionCount,
            "evaluation_facts": (
                request.evaluationFacts.model_dump(mode="json", exclude_none=True)
                if request.evaluationFacts
                else None
            ),
            "history_topics": request.historyTopics,
            "generation_targets": [],
            "raw_candidates": [],
            "result": None,
        }
        try:
            final = await self._compiled.ainvoke(initial)
            result = final.get("result")
            candidates = [
                CandidatePoolItem(**candidate)
                for candidate in (result or {}).get("candidates") or []
            ]
            # generate_questions 对单目标 LLM 失败做降级丢弃（尽力而为的缓存）；
            # 若存在生成目标但原始候选为空，说明所有目标都失败（LLM 整体不可用），
            # 此时返回 success=false 便于调用方重试，与 interview-policy.md 5.2
            # 「仍然失败→返回错误，允许用户重试」保持一致。
            if final.get("generation_targets") and not final.get("raw_candidates"):
                return GenerateCandidatePoolResponse(
                    success=False,
                    errorMessage="候选池生成失败：所有生成目标均未成功，请稍后重试",
                )
            return GenerateCandidatePoolResponse(success=True, candidates=candidates)
        except AppError as exc:
            # 确定性业务错误直接返回，不隐藏可读原因
            return GenerateCandidatePoolResponse(success=False, errorMessage=exc.message)
        except Exception as exc:  # noqa: BLE001 - 对外隐藏内部堆栈，保留统一可读错误
            log.exception(
                "候选池生成图执行异常 session_id=%s question_id=%s",
                request.sessionId,
                request.questionId,
            )
            return GenerateCandidatePoolResponse(
                success=False, errorMessage=f"候选池生成失败：{exc}"
            )
