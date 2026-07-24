"""简历解析 API。"""

from hmac import compare_digest
from typing import Annotated

from fastapi import APIRouter, Depends, Security
from fastapi.security import APIKeyHeader

from app.core.config import Settings, get_settings
from app.core.errors import AppError, ErrorResponse
from app.schemas.resume import ParseResumeRequest, ParseResumeResponse
from app.services.resume_parser import parse_resume

router = APIRouter(prefix="/resume", tags=["简历解析"])
api_key_header = APIKeyHeader(
    name="X-API-Key",
    description="固定 API Key 认证",
    auto_error=False,
)


async def require_ai_service_api_key(
    api_key: Annotated[str | None, Security(api_key_header)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    """校验 Spring Boot 到 AI 服务的固定密钥，避免解析接口被匿名调用。"""
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
    "/parse",
    operation_id="parseResume",
    response_model=ParseResumeResponse,
    response_description="解析成功",
    summary="解析简历 PDF",
    dependencies=[Depends(require_ai_service_api_key)],
    responses={
        401: {"model": ErrorResponse, "description": "接口鉴权失败"},
        422: {"model": ErrorResponse, "description": "请求参数校验失败"},
        500: {"model": ErrorResponse, "description": "AI 服务鉴权配置缺失"},
    },
)
async def parse_resume_endpoint(request: ParseResumeRequest) -> ParseResumeResponse:
    """调用统一 LangGraph 流程解析文本型或扫描型中文 PDF。"""
    return await parse_resume(
        file_url=str(request.fileUrl),
        mime_type=request.mimeType,
        trace_id=str(request.traceId),
    )
