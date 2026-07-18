from uuid import UUID, uuid4

from fastapi import FastAPI, Request

TRACE_ID_HEADER = "X-Trace-Id"


def resolve_trace_id(value: str | None) -> str:
    if value:
        try:
            return str(UUID(value.strip()))
        except ValueError:
            pass
    return str(uuid4())


def register_trace_middleware(app: FastAPI) -> None:
    @app.middleware("http")
    async def add_trace_id(request: Request, call_next):
        trace_id = resolve_trace_id(request.headers.get(TRACE_ID_HEADER))
        request.state.trace_id = trace_id
        response = await call_next(request)
        response.headers[TRACE_ID_HEADER] = trace_id
        return response
