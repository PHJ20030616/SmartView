"""画像分析 API。"""

from hmac import compare_digest
from typing import Annotated

from fastapi import APIRouter, Depends, Security
from fastapi.security import APIKeyHeader

from app.core.config import Settings, get_settings
from app.core.errors import AppError, ErrorResponse
from app.core.trace import reset_trace_id, set_trace_id
from app.schemas.profile import AnalyzeProfileRequest, AnalyzeProfileResponse
from app.services.profile_analyzer import analyze_profile_latest

router = APIRouter(prefix="/profile", tags=["画像分析"])
api_key_header = APIKeyHeader(
    name="X-API-Key",
    description="固定 API Key 认证",
    auto_error=False,
)


async def require_ai_service_api_key(
    api_key: Annotated[str | None, Security(api_key_header)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    """校验 Spring Boot 到 AI 服务的固定密钥，避免画像分析接口被匿名调用。"""
    expected_key = settings.ai_service_api_key.get_secret_value().strip()
    if not expected_key:
        raise AppError(
            "AI 服务未配置接口鉴权密钥，请检查 AI_SERVICE_API_KEY",
            code="AI_AUTH_CONFIG_MISSING",
            status_code=500,
        )
    if not api_key or not compare_digest(api_key, expected_key):
        raise AppError(
            "接口鉴权失败，请提供有效的 X-API-Key",
            code="AUTHENTICATION_FAILED",
            status_code=401,
        )


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
        import logging

        logging.getLogger(__name__).exception(
            "画像分析 HTTP 端点异常 profileId=%s direction=%s",
            request.resumeProfileId,
            request.roleDirection,
        )
        return AnalyzeProfileResponse(
            success=False, errorMessage="画像分析失败，请稍后重试"
        )
    finally:
        reset_trace_id(token)
