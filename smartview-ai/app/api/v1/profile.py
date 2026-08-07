"""画像分析 API。"""

import logging

from fastapi import APIRouter, Depends

from app.api.v1.deps import require_ai_service_api_key
from app.core.errors import AppError, ErrorResponse
from app.core.trace import reset_trace_id, set_trace_id
from app.schemas.profile import AnalyzeProfileRequest, AnalyzeProfileResponse
from app.services.profile_analyzer import analyze_profile_latest

log = logging.getLogger(__name__)

router = APIRouter(prefix="/profile", tags=["画像分析"])


@router.post(
    "/analyze",
    operation_id="analyzeProfile",
    response_model=AnalyzeProfileResponse,
    response_description="分析成功",
    summary="生成方向画像分析",
    dependencies=[Depends(require_ai_service_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "接口鉴权失败"},
        422: {"model": ErrorResponse, "description": "请求参数校验失败"},
        500: {"model": ErrorResponse, "description": "AI 服务鉴权配置缺失"},
    },
)
async def analyze_profile_endpoint(
    request: AnalyzeProfileRequest,
) -> AnalyzeProfileResponse:
    """同步生成方向画像分析（与 MQ worker 共享同一服务，供契约校验与人工调试）。

    契约未携带 profileVersion，因此按"分析该画像所属简历文件最新已确认版本"语义执行；
    生产主链路仍是 MQ worker，携带精确的画像版本号。
    """
    # 把契约请求体中的 traceId 注入日志上下文，与 MQ worker 保持一致，
    # 使端点内日志与响应头 X-Trace-Id 都能关联到调用方链路。
    token = set_trace_id(str(request.traceId))
    try:
        analysis = await analyze_profile_latest(
            resume_profile_id=str(request.resumeProfileId),
            role_direction=request.roleDirection,
        )
        return AnalyzeProfileResponse(
            success=True,
            **analysis.model_dump(mode="json", exclude_none=True),
        )
    except AppError as exc:
        # 对外隐藏底层堆栈，返回统一可读失败响应。
        return AnalyzeProfileResponse(success=False, errorMessage=exc.message)
    except Exception:  # noqa: BLE001 - 对外隐藏内部堆栈，保留统一可读错误
        log.exception(
            "画像分析 HTTP 端点异常 profileId=%s direction=%s",
            request.resumeProfileId,
            request.roleDirection,
        )
        return AnalyzeProfileResponse(
            success=False, errorMessage="画像分析失败，请稍后重试"
        )
    finally:
        reset_trace_id(token)
