"""
全局异常处理模块

定义自定义异常类和全局异常处理器,统一错误响应格式。
"""
import logging
from uuid import UUID

from fastapi import FastAPI, Request, status
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

log = logging.getLogger(__name__)


class ErrorResponse(BaseModel):
    """跨服务接口统一错误响应。"""

    error: str
    message: str
    traceId: UUID


class AppError(Exception):
    """
    应用业务异常类

    用于业务逻辑中抛出的可预期异常,包含错误码、消息和 HTTP 状态码。
    """
    def __init__(
        self,
        message: str,
        *,
        code: str = "BUSINESS_ERROR",
        status_code: int = status.HTTP_400_BAD_REQUEST,
    ) -> None:
        """
        初始化业务异常

        参数:
            message: 错误消息
            code: 错误码（默认 BUSINESS_ERROR）
            status_code: HTTP 状态码（默认 400）
        """
        super().__init__(message)
        self.message = message
        self.code = code
        self.status_code = status_code


def _error_body(request: Request, code: str, message: str) -> dict[str, str]:
    """
    构造统一的错误响应体

    参数:
        request: 请求对象
        code: 错误码
        message: 错误消息

    返回:
        dict: 包含错误码、消息和追踪 ID 的字典
    """
    return {
        "error": code,
        "message": message,
        "traceId": request.state.trace_id,
    }


def register_exception_handlers(app: FastAPI) -> None:
    """
    注册全局异常处理器

    捕获并处理以下异常:
    1. AppError - 业务异常,返回自定义错误码和消息
    2. RequestValidationError - 参数校验异常,返回 422 状态码
    3. Exception - 未预期异常,返回 500 状态码,隐藏堆栈信息

    参数:
        app: FastAPI 应用实例
    """
    @app.exception_handler(AppError)
    async def handle_app_error(request: Request, exc: AppError) -> JSONResponse:
        """处理业务异常"""
        return JSONResponse(status_code=exc.status_code, content=_error_body(request, exc.code, exc.message))

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        """处理参数校验异常"""
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content=_error_body(request, "VALIDATION_FAILED", "请求参数校验失败，请检查输入内容"),
        )

    @app.exception_handler(Exception)
    async def handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
        """处理未预期异常"""
        # 兜底异常不向调用方暴露堆栈，详细信息仅保留在服务端日志。
        log.exception("Unhandled AI service exception", exc_info=exc)
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content=_error_body(request, "INTERNAL_ERROR", "AI 服务暂时不可用，请稍后再试"),
        )
