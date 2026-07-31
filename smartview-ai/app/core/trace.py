"""
请求追踪模块

为每个请求生成或解析追踪 ID（Trace ID），用于分布式系统的链路追踪和日志关联。
"""
import contextvars
import logging
import time
from uuid import UUID, uuid4

from fastapi import FastAPI, Request

# 追踪 ID 请求头名称
TRACE_ID_HEADER = "X-Trace-Id"

# 保存当前请求/任务追踪 ID 的上下文变量；日志过滤器读取它以自动附加到每条日志
TRACE_ID_CONTEXT: contextvars.ContextVar[str] = contextvars.ContextVar(
    "trace_id", default=""
)

# 请求级访问日志使用独立 logger，便于按名称过滤和查看请求执行状态
access_log = logging.getLogger("smartview.access")


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


def set_trace_id(trace_id: str) -> contextvars.Token:
    """把追踪 ID 写入当前上下文，返回用于恢复上下文的 token。"""
    return TRACE_ID_CONTEXT.set(trace_id)


def reset_trace_id(token: contextvars.Token) -> None:
    """恢复调用 set_trace_id 之前的上下文，避免追踪 ID 污染并发请求或后续任务。"""
    TRACE_ID_CONTEXT.reset(token)


def register_trace_middleware(app: FastAPI) -> None:
    """
    注册追踪 ID 中间件

    在 HTTP 中间件中为每个请求解析或生成追踪 ID，
    并将其存储到请求状态中，同时在响应头中返回。

    中间件同时输出请求级访问日志（收到请求 / 请求处理完成），
    包含追踪 ID 与耗时，用于观察 Spring Boot 调用的执行状态。

    参数:
        app: FastAPI 应用实例
    """
    @app.middleware("http")
    async def add_trace_id(request: Request, call_next):
        # 从请求头解析或生成追踪 ID，并写入当前上下文供日志过滤器使用
        trace_id = resolve_trace_id(request.headers.get(TRACE_ID_HEADER))
        request.state.trace_id = trace_id
        token = set_trace_id(trace_id)

        client = request.client.host if request.client else "-"
        path = request.url.path
        access_log.info(
            "收到请求 method=%s path=%s client=%s",
            request.method,
            path,
            client,
        )
        start = time.perf_counter()
        response = None
        try:
            response = await call_next(request)
            return response
        except Exception:
            duration_ms = (time.perf_counter() - start) * 1000
            access_log.exception(
                "请求处理异常 method=%s path=%s duration_ms=%.1f",
                request.method,
                path,
                duration_ms,
            )
            raise
        finally:
            if response is not None:
                # 在响应头中返回追踪 ID，便于调用方按链路关联日志
                response.headers[TRACE_ID_HEADER] = trace_id
                duration_ms = (time.perf_counter() - start) * 1000
                # 先记录完成日志再恢复上下文，确保该日志也能携带 trace_id
                access_log.info(
                    "请求处理完成 method=%s path=%s status=%s duration_ms=%.1f",
                    request.method,
                    path,
                    response.status_code,
                    duration_ms,
                )
            # 恢复上下文，避免追踪 ID 泄漏到并发请求或后续任务
            reset_trace_id(token)
