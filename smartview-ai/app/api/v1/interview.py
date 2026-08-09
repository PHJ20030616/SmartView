"""面试流程 API。

对外暴露给 Spring Boot 的面试 AI 能力（同步 HTTP）：
- POST /api/v1/interview/first-question：生成面试首题
- POST /api/v1/interview/candidate-pool：生成候选问题池

职责边界（docs/interview-policy.md 1.x）：本模块只返回题目事实（正文、主题、
来源、期望要点、引用），不做任何业务决策；阶段计划由 Spring 端生成后透传。
"""

from __future__ import annotations

from functools import lru_cache

from fastapi import APIRouter, Depends

from app.api.v1.deps import require_ai_service_api_key
from app.core.errors import ErrorResponse
from app.core.trace import reset_trace_id, set_trace_id
from app.graphs.candidate_pool_graph import CandidatePoolGraph
from app.graphs.evaluate_answer_graph import EvaluateAnswerGraph
from app.graphs.interview_graph import FirstQuestionGraph
from app.schemas.interview import (
    EvaluateAnswerRequest,
    EvaluateAnswerResponse,
    GenerateCandidatePoolRequest,
    GenerateCandidatePoolResponse,
    GenerateFirstQuestionRequest,
    QuestionResponse,
)

router = APIRouter(prefix="/interview", tags=["面试流程"])


@lru_cache(maxsize=1)
def _get_graph() -> FirstQuestionGraph:
    """缓存单例图实例，避免每个请求重复创建 MySQL 引擎并编译 LangGraph。"""
    return FirstQuestionGraph()


@lru_cache(maxsize=1)
def _get_candidate_pool_graph() -> CandidatePoolGraph:
    """缓存候选池生成图单例，避免每次请求重复编译 LangGraph。"""
    return CandidatePoolGraph()


@lru_cache(maxsize=1)
def _get_evaluate_graph() -> EvaluateAnswerGraph:
    """缓存回答评估图单例，避免每次请求重复编译 LangGraph。"""
    return EvaluateAnswerGraph()


@router.post(
    "/first-question",
    operation_id="generateFirstQuestion",
    response_model=QuestionResponse,
    response_description="生成成功。业务失败（画像不存在/未确认、LLM 服务异常等）"
    "同样返回 200，通过 QuestionResponse.success=false 与 errorMessage 表达。",
    summary="生成首题",
    dependencies=[Depends(require_ai_service_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "接口鉴权失败"},
        422: {"model": ErrorResponse, "description": "请求参数校验失败"},
        500: {"model": ErrorResponse, "description": "AI 服务鉴权配置缺失"},
    },
)
async def generate_first_question(
    request: GenerateFirstQuestionRequest,
) -> QuestionResponse:
    """同步生成面试首题（Spring 创建会话时调用）。

    把请求携带的 traceId 注入日志上下文，使生成流程内日志与响应头 X-Trace-Id
    都能关联到调用方链路；业务失败在图中转为 success=false，此处只需清理上下文。
    """
    token = set_trace_id(str(request.traceId))
    try:
        return await _get_graph().generate(request)
    finally:
        reset_trace_id(token)


@router.post(
    "/candidate-pool",
    operation_id="generateCandidatePool",
    response_model=GenerateCandidatePoolResponse,
    response_description="生成成功。业务失败同样返回 200，通过 success=false 与 errorMessage 表达。",
    summary="生成候选问题池",
    dependencies=[Depends(require_ai_service_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "接口鉴权失败"},
        422: {"model": ErrorResponse, "description": "请求参数校验失败"},
        500: {"model": ErrorResponse, "description": "AI 服务鉴权配置缺失"},
    },
)
async def generate_candidate_pool(
    request: GenerateCandidatePoolRequest,
) -> GenerateCandidatePoolResponse:
    """生成候选问题池（Spring 异步预生成 / 5.4 evaluate 内联复用）。

    候选池只提供备选问题，不决定下一步；最终动作由 Spring StagePolicyEngine 决定。
    把请求携带的 traceId 注入日志上下文，使生成流程内日志可关联到调用方链路。
    """
    token = set_trace_id(str(request.traceId))
    try:
        return await _get_candidate_pool_graph().generate(request)
    finally:
        reset_trace_id(token)


@router.post(
    "/evaluate",
    operation_id="evaluateAnswer",
    response_model=EvaluateAnswerResponse,
    response_description="评估成功。业务失败同样返回 200，通过 success=false 与 errorMessage 表达。",
    summary="评估回答并生成追问候选",
    dependencies=[Depends(require_ai_service_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "接口鉴权失败"},
        422: {"model": ErrorResponse, "description": "请求参数校验失败"},
        500: {"model": ErrorResponse, "description": "AI 服务鉴权配置缺失"},
    },
)
async def evaluate_answer(request: EvaluateAnswerRequest) -> EvaluateAnswerResponse:
    """评估回答并同步生成追问候选（Spring 提交回答时调用）。

    只返回评估事实与追问候选池（0-2 道），不返回任何业务决策字段；
    最终动作由 Spring StagePolicyEngine 决定。
    """
    token = set_trace_id(str(request.traceId))
    try:
        return await _get_evaluate_graph().evaluate(request)
    finally:
        reset_trace_id(token)
