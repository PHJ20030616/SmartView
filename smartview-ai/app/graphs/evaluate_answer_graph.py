"""回答评估 LangGraph 状态机。

图流程（两条分支并行，最后汇合；两条分支都是 1 跳，原因见 _build 注释）：

    START ─┬─ evaluate_answer ────────────────────────┐
           └─ plan_and_generate(目标计算 + 候选生成) ──┴─ build_candidate_pool → END

并行依据（develop_plan/plan_1.3.md）：追问候选只依赖题目、候选人回答与期望要点，
不依赖评估结论；实测两臂盲评打平，而串行两轮合计约 22.8s，并行后收敛为
max(评估, 追问生成)。得分门控（<40 不追问、40-70 用 GAP、>70 用 DEEP）由 Spring
StagePolicyEngine 在决策时按 followUpKind 执行。

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
        """组装并编译 LangGraph 图（追问候选生成复用候选池图节点，DRY）。

        两条分支从 START 并行展开，汇聚于 build_candidate_pool。

        两个必须遵守的约束（都是 LangGraph Pregel 执行模型决定的，实测踩过）：
        1. **两条分支必须等长（各 1 跳）**：Pregel 是"超步（superstep）内并发、超步之间设栅栏"，
           若生成分支是 stage_controller → generate_questions 两跳，它会落在超步 2，
           而超步 1 要等评估分支跑完才结束——实际退化成串行（实测评估 14.8s 后才开始生成）。
           因此目标计算并入生成节点（见 _plan_and_generate_wrapper），两分支同为 1 跳。
        2. **汇合必须用 `add_edge([...], node)` 多源边**：LangGraph 的多源边语义是"等全部来源完成"，
           而拆成两条 `add_edge(源, 目标)` 会让汇合节点被先到的分支提前触发（实测会执行两次，
           第一次读到尚未生成的空候选）。

        评估分支抛错时整图失败，调用方保持"评估失败不落库、允许重试"的语义。
        """
        builder = StateGraph(EvaluateAnswerState)
        builder.add_node("evaluate_answer", self._evaluate_wrapper)
        builder.add_node("plan_and_generate", self._plan_and_generate_wrapper)
        builder.add_node("build_candidate_pool", lambda state: build_candidate_pool(state))
        builder.add_edge(START, "evaluate_answer")
        builder.add_edge(START, "plan_and_generate")
        builder.add_edge(["evaluate_answer", "plan_and_generate"], "build_candidate_pool")
        builder.add_edge("build_candidate_pool", END)
        return builder.compile()

    async def _evaluate_wrapper(self, state: EvaluateAnswerState) -> dict[str, Any]:
        return await evaluate_answer(state, self.settings)

    async def _plan_and_generate_wrapper(self, state: EvaluateAnswerState) -> dict[str, Any]:
        """目标计算 + 候选生成合并为同一节点（保证与评估同处一个超步，见 _build 注释）。

        目标计算本身是确定性的、无 LLM 调用，合并后只是少一跳，不改变职责边界：
        仍然由 compute_generation_targets 产出目标、由 generate_questions 生成候选。
        """
        planned = dict(state)
        planned.update(compute_generation_targets(planned))
        result = await generate_questions(planned, self.settings)
        # 显式列出键而不是 **result：generate_questions 将来新增键时不会静默覆盖
        # generation_targets（候选池组装与"全部目标失败"判断都依赖目标列表）
        return {
            "generation_targets": planned.get("generation_targets") or [],
            "raw_candidates": result.get("raw_candidates") or [],
        }

    async def evaluate(self, request: EvaluateAnswerRequest) -> EvaluateAnswerResponse:
        """执行回答评估并返回契约响应（评估事实 + 追问候选）。

        追问候选与评估并行产出，因此响应里的候选不再受得分门控：低分回答同样可能带回
        候选，由 Spring StagePolicyEngine 按 score 与 followUpKind 决定是否采用。
        """
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
