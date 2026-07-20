"""
请求追踪模块

为每个请求生成或解析追踪 ID（Trace ID），用于分布式系统的链路追踪和日志关联。
"""
from uuid import UUID, uuid4

from fastapi import FastAPI, Request

# 追踪 ID 请求头名称
TRACE_ID_HEADER = "X-Trace-Id"


def resolve_trace_id(value: str | None) -> str:
    """
    解析或生成追踪 ID

    如果请求头包含有效的 UUID 格式的追踪 ID，则使用该 ID；
    否则生成一个新的 UUID 作为追踪 ID。

    参数:
        value: 请求头中的追踪 ID 值

    返回:
        str: 有效的追踪 ID（UUID 字符串）
    """
    if value:
        try:
            return str(UUID(value.strip()))
        except ValueError:
            pass
    return str(uuid4())


def register_trace_middleware(app: FastAPI) -> None:
    """
    注册追踪 ID 中间件

    在 HTTP 中间件中为每个请求解析或生成追踪 ID，
    并将其存储在请求状态中，同时在响应头中返回。

    参数:
        app: FastAPI 应用实例
    """
    @app.middleware("http")
    async def add_trace_id(request: Request, call_next):
        # 从请求头解析或生成追踪 ID
        trace_id = resolve_trace_id(request.headers.get(TRACE_ID_HEADER))
        # 存储到请求状态中，供后续处理使用
        request.state.trace_id = trace_id
        response = await call_next(request)
        # 在响应头中返回追踪 ID
        response.headers[TRACE_ID_HEADER] = trace_id
        return response
