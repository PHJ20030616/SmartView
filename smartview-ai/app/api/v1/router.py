"""
API v1 路由聚合模块

将所有 v1 版本的子路由聚合到统一的 API 路由器中。
"""
from fastapi import APIRouter

from app.api.v1.health import router as health_router
from app.api.v1.resume import router as resume_router

# 创建 API v1 主路由器
api_router = APIRouter()

# 注册健康检查路由
api_router.include_router(health_router)
api_router.include_router(resume_router)
