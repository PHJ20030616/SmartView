"""API v1 公共依赖。

将跨服务鉴权依赖抽取为公共模块，供画像分析、面试流程等需要
X-API-Key 校验的接口复用，避免各模块重复实现鉴权逻辑。
"""

from __future__ import annotations

from hmac import compare_digest
from typing import Annotated

from fastapi import Depends, Security
from fastapi.security import APIKeyHeader

from app.core.config import Settings, get_settings
from app.core.errors import AppError

# 固定 API Key 请求头定义，auto_error=False 以便校验失败时给出统一错误响应
api_key_header = APIKeyHeader(
    name="X-API-Key",
    description="固定 API Key 认证",
    auto_error=False,
)


async def require_ai_service_api_key(
    api_key: Annotated[str | None, Security(api_key_header)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    """校验 Spring Boot 到 AI 服务的固定密钥，避免接口被匿名调用。"""
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
