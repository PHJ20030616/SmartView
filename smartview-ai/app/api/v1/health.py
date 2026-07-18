from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(tags=["健康检查"])


class HealthResponse(BaseModel):
    status: Literal["UP", "DOWN"]
    timestamp: datetime = None


@router.get(
    "/health",
    operation_id="aiHealthCheck",
    response_model=HealthResponse,
    summary="AI 服务健康状态检查",
)
def health() -> HealthResponse:
    # 健康检查严格对齐 contracts/ai-api/openapi.yaml，避免跨服务契约外字段扩散。
    return HealthResponse(
        status="UP",
        timestamp=datetime.now(UTC),
    )
