"""
健康检查端点模块

提供 AI 服务的健康状态检查接口，用于监控和负载均衡。
"""
from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(tags=["健康检查"])


class HealthResponse(BaseModel):
    """
    健康检查响应模型

    属性:
        status: 服务状态（UP 表示正常运行，DOWN 表示故障）
        timestamp: 检查时间戳
    """
    status: Literal["UP", "DOWN"]
    timestamp: datetime = None


@router.get(
    "/health",
    operation_id="aiHealthCheck",
    response_model=HealthResponse,
    summary="AI 服务健康状态检查",
)
def health() -> HealthResponse:
    """
    健康检查端点

    返回 AI 服务的运行状态和当前时间戳。
    严格对齐 contracts/ai-api/openapi.yaml 契约，避免跨服务契约外字段扩散。

    返回:
        HealthResponse: 健康状态响应
    """
    return HealthResponse(
        status="UP",
        timestamp=datetime.now(UTC),
    )
