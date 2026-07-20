"""
应用配置管理模块

基于 Pydantic 的配置管理，支持从环境变量和 .env 文件读取配置。
"""
from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    应用配置类

    使用 Pydantic BaseSettings 自动从环境变量和 .env 文件读取配置。
    支持类型验证和默认值设置。
    """
    # 应用基本信息
    app_name: str = "SmartView AI API"
    app_version: str = "0.1.0"
    environment: str = Field(default="local", alias="SMARTVIEW_AI_ENV")

    # API 路由配置
    api_v1_prefix: str = "/api/v1"

    # 日志级别
    log_level: str = "INFO"

    # CORS 跨域配置
    cors_allow_origins: list[str] = Field(default_factory=list)

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",  # 忽略额外的环境变量
        populate_by_name=True,  # 支持字段名和别名
    )


@lru_cache
def get_settings() -> Settings:
    """
    获取配置单例

    使用 LRU 缓存确保配置只加载一次，提高性能。

    返回:
        Settings: 配置实例
    """
    return Settings()
