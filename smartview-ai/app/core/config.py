"""
应用配置管理模块

基于 Pydantic 的配置管理，支持从环境变量和 .env 文件读取配置。
"""
from functools import lru_cache

from pydantic import Field, SecretStr
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

    # FastAPI 只允许 Spring Boot 携带该密钥调用，空值时会拒绝所有受保护请求。
    ai_service_api_key: SecretStr = Field(
        default=SecretStr(""),
        alias="AI_SERVICE_API_KEY",
    )

    # DeepSeek 配置。密钥使用 SecretStr，避免在配置对象 repr 或异常信息中意外泄露。
    deepseek_api_key: SecretStr = Field(default=SecretStr(""))
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-chat"
    deepseek_timeout_seconds: float = Field(default=60.0, gt=0)
    deepseek_max_tokens: int = Field(default=4096, gt=0)
    deepseek_temperature: float = Field(default=0.1, ge=0, le=2)
    deepseek_max_input_characters: int = Field(default=60_000, gt=0)

    # 文档处理限制用于防止异常大的远程文件和 PDF 消耗过多内存或 CPU。
    resume_max_file_bytes: int = Field(default=10 * 1024 * 1024, gt=0)
    resume_max_pages: int = Field(default=20, gt=0)
    resume_ocr_dpi: int = Field(default=200, ge=72, le=600)
    resume_min_useful_page_characters: int = Field(default=20, ge=1)
    resume_max_page_dimension: int = Field(default=10000, gt=0)
    resume_max_page_pixels: int = Field(default=25_000_000, gt=0)
    # 生产环境建议配置 MinIO/S3 域名白名单；为空时仍会拒绝解析到内网的地址。
    resume_allowed_hosts: list[str] = Field(default_factory=list)
    resume_max_redirects: int = Field(default=3, ge=0, le=10)
    resume_download_timeout_seconds: float = Field(default=60.0, gt=0)

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
