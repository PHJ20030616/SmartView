"""
SmartView AI 服务应用入口

基于 FastAPI 构建的 AI 服务，为 Spring Boot 后端提供 AI 能力。
只对 Spring Boot 后端暴露 API，不直接对外提供服务。
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.v1.router import api_router
from app.core.config import get_settings
from app.core.errors import register_exception_handlers
from app.core.logging import configure_logging
from app.core.trace import register_trace_middleware


def create_app() -> FastAPI:
    """
    创建并配置 FastAPI 应用实例

    返回:
        FastAPI: 配置完成的应用实例
    """
    settings = get_settings()
    configure_logging(settings.log_level)

    app = FastAPI(
        title=settings.app_name,
        version=settings.app_version,
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

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
