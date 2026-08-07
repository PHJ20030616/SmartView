"""面试流程 API。

对外暴露给 Spring Boot 的面试 AI 能力（同步 HTTP）：
- POST /api/v1/interview/first-question：生成面试首题

职责边界（docs/interview-policy.md 1.x）：本模块只返回题目事实（正文、主题、
来源、期望要点、引用），不做任何业务决策；阶段计划由 Spring 端生成后透传。
"""

from __future__ import annotations

from functools import lru_cache

from fastapi import APIRouter, Depends

from app.api.v1.deps import require_ai_service_api_key
from app.core.errors import ErrorResponse
from app.core.trace import reset_trace_id, set_trace_id
from app.graphs.interview_graph import FirstQuestionGraph
from app.schemas.interview import GenerateFirstQuestionRequest, QuestionResponse

router = APIRouter(prefix="/interview", tags=["面试流程"])


@lru_cache(maxsize=1)
def _get_graph() -> FirstQuestionGraph:
    """缓存单例图实例，避免每个请求重复创建 MySQL 引擎并编译 LangGraph。"""
    return FirstQuestionGraph()


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
