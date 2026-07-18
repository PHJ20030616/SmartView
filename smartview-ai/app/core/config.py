from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    app_name: str = "SmartView AI API"
    app_version: str = "0.1.0"
    environment: str = Field(default="local", alias="SMARTVIEW_AI_ENV")
    api_v1_prefix: str = "/api/v1"
    log_level: str = "INFO"
    cors_allow_origins: list[str] = Field(default_factory=list)

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        populate_by_name=True,
    )


@lru_cache
def get_settings() -> Settings:
    return Settings()
