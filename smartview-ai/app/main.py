"""
SmartView AI 服务应用入口

基于 FastAPI 构建的 AI 服务，为 Spring Boot 后端提供 AI 能力。
只对 Spring Boot 后端暴露 API，不直接对外提供服务。
"""
from typing import Any

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.router import api_router
from app.core.config import get_settings
from app.core.errors import register_exception_handlers
from app.core.logging import configure_logging
from app.core.trace import register_trace_middleware


def _normalize_openapi_3_0(value: Any) -> Any:
    """将 Pydantic v2 生成的 3.1 Schema 兼容转换为 OpenAPI 3.0 写法。"""
    if isinstance(value, list):
        return [_normalize_openapi_3_0(item) for item in value]
    if not isinstance(value, dict):
        return value

    normalized = {
        key: _normalize_openapi_3_0(item)
        for key, item in value.items()
        if key not in {"const"}
    }
    if "const" in value:
        normalized["enum"] = [value["const"]]

    any_of = normalized.get("anyOf")
    if isinstance(any_of, list):
        non_null = [
            item
            for item in any_of
            if not (isinstance(item, dict) and item.get("type") == "null")
        ]
        if len(non_null) == 1 and len(non_null) != len(any_of):
            branch = non_null[0]
            normalized.pop("anyOf", None)
            if isinstance(branch, dict) and "$ref" in branch:
                normalized["allOf"] = [branch]
            elif isinstance(branch, dict):
                normalized.update(branch)
            normalized["nullable"] = True

    return normalized


# X-Trace-Id 响应头声明：与 contracts/ai-api/openapi.yaml 保持一致（契约优先）
_TRACE_ID_RESPONSE_HEADER = "X-Trace-Id"
_TRACE_ID_HEADER_REF = {"X-Trace-Id": {"$ref": "#/components/headers/XTraceId"}}
_TRACE_ID_HEADER_COMPONENT = {
    "description": "链路追踪 ID，与请求头 X-Trace-Id 一致，用于跨服务关联日志",
    "schema": {"type": "string", "format": "uuid"},
}


def _inject_trace_id_response_headers(schema: dict[str, Any]) -> None:
    """把 X-Trace-Id 响应头声明注入生成的 OpenAPI。

    trace 中间件会给所有响应追加 X-Trace-Id 响应头，属于跨服务可见行为，
    因此 OpenAPI 文档与契约均需声明该响应头。
    """
    for path_item in schema.get("paths", {}).values():
        if not isinstance(path_item, dict):
            continue
        for operation in path_item.values():
            if not isinstance(operation, dict) or not isinstance(
                operation.get("responses"), dict
            ):
                continue
            for response in operation["responses"].values():
                if isinstance(response, dict):
                    response.setdefault("headers", dict(_TRACE_ID_HEADER_REF))
    components = schema.setdefault("components", {})
    components.setdefault("headers", {})["XTraceId"] = dict(
        _TRACE_ID_HEADER_COMPONENT
    )


def create_app() -> FastAPI:
    """
    创建并配置 FastAPI 应用实例

    返回:
        FastAPI: 配置完成的应用实例
    """
    settings = get_settings()
    configure_logging(
        settings.log_level,
        log_dir=settings.log_dir,
        log_file_enabled=settings.log_file_enabled,
        log_file_max_bytes=settings.log_file_max_bytes,
        log_file_backup_count=settings.log_file_backup_count,
        log_file_name="smartview-api.log",
    )

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )
    # 项目契约和 Spring Boot 代码生成链以 OpenAPI 3.0 为兼容基线。
    app.openapi_version = "3.0.3"
    original_openapi = app.openapi

    def openapi_3_0() -> dict[str, Any]:
        if app.openapi_schema is None:
            schema = _normalize_openapi_3_0(original_openapi())
            _inject_trace_id_response_headers(schema)
            app.openapi_schema = schema
        return app.openapi_schema

    app.openapi = openapi_3_0

    # 配置 CORS 跨域支持
    if settings.cors_allow_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_allow_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    # 注册全局异常处理器
    register_exception_handlers(app)
    # 注册追踪 ID 中间件
    register_trace_middleware(app)
    # 注册 API 路由
    app.include_router(api_router, prefix=settings.api_v1_prefix)
    return app


# 创建应用实例
app = create_app()
