"""
应用配置管理模块

基于 Pydantic 的配置管理，支持从环境变量和 .env 文件读取配置。
"""
from functools import lru_cache
from urllib.parse import urlparse

from pydantic import Field, SecretStr, field_validator
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

    # 日志文件输出配置：控制台始终输出，文件日志可按需关闭
    log_file_enabled: bool = Field(default=True, alias="LOG_FILE_ENABLED")
    log_dir: str = Field(default="./logs", alias="LOG_DIR")
    log_file_max_bytes: int = Field(
        default=50 * 1024 * 1024, alias="LOG_FILE_MAX_BYTES", gt=0
    )
    log_file_backup_count: int = Field(
        default=7, alias="LOG_FILE_BACKUP_COUNT", ge=0
    )

    # RabbitMQ 使用与 Spring Boot 相同的拆分配置，避免两端因 AMQP URL 不一致而连接到不同账号。
    rabbitmq_host: str = Field(default="localhost", alias="RABBITMQ_HOST")
    rabbitmq_port: int = Field(default=5672, alias="RABBITMQ_AMQP_PORT", gt=0)
    rabbitmq_username: str = Field(default="smartview", alias="RABBITMQ_DEFAULT_USER")
    rabbitmq_password: SecretStr = Field(
        default=SecretStr("123456"),
        alias="RABBITMQ_DEFAULT_PASS",
    )
    rabbitmq_vhost: str = Field(default="smartview", alias="RABBITMQ_DEFAULT_VHOST")
    rabbitmq_exchange: str = "smartview.direct"
    rabbitmq_resume_parse_queue: str = "smartview.resume.parse"
    rabbitmq_resume_parse_routing_key: str = "resume.parse.task"
    rabbitmq_resume_result_routing_key: str = "resume.parse.result"
    rabbitmq_resume_vectorize_queue: str = "smartview.resume.vectorize.v1"
    rabbitmq_resume_vectorize_routing_key: str = "resume.vectorize.task"
    rabbitmq_dead_letter_exchange: str = "smartview.dlx"
    rabbitmq_resume_vectorize_dead_letter_queue: str = "smartview.resume.vectorize.dlq"
    rabbitmq_resume_vectorize_dead_letter_routing_key: str = (
        "resume.vectorize.task.dlq"
    )
    rabbitmq_resume_vectorize_result_routing_key: str = "resume.vectorize.result"
    rabbitmq_task_max_retries: int = Field(default=3, ge=0, le=3)
    rabbitmq_retry_delay_seconds: float = Field(default=1.0, gt=0)
    rabbitmq_reconnect_delay_seconds: float = Field(default=5.0, gt=0)
    rabbitmq_prefetch_count: int = Field(default=1, gt=0)

    # 向量入库依赖；FastAPI 只读取已确认画像，不直接接受前端传入的完整简历。
    mysql_host: str = Field(default="localhost", alias="MYSQL_HOST")
    mysql_port: int = Field(default=3306, alias="MYSQL_PORT", gt=0)
    mysql_database: str = Field(default="smartview", alias="MYSQL_DATABASE")
    mysql_username: str = Field(default="smartview", alias="MYSQL_USERNAME")
    mysql_password: SecretStr = Field(
        default=SecretStr("123456"),
        alias="MYSQL_PASSWORD",
    )
    chroma_persist_directory: str = Field(
        default="./data/chroma",
        alias="CHROMA_PERSIST_DIRECTORY",
    )
    chroma_collection_name: str = Field(
        default="resume_profile_chunks",
        alias="CHROMA_COLLECTION_NAME",
    )
    resume_vector_chunk_size: int = Field(
        default=800,
        alias="RESUME_VECTOR_CHUNK_SIZE",
        gt=0,
    )
    resume_vector_chunk_overlap: int = Field(
        default=120,
        alias="RESUME_VECTOR_CHUNK_OVERLAP",
        ge=0,
    )

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
    # 与当前 DeepSeek Chat Completions 可用模型保持一致，避免未配置 .env 时请求已废弃模型。
    deepseek_model: str = "deepseek-v4-flash"
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
    # 生产环境必须按“协议 + 主机 + 端口”配置 MinIO/S3 来源白名单，例如
    # ["https://minio.example.com:443"]。空白名单时仍会拒绝解析到内网的地址。
    resume_allowed_origins: list[str] = Field(default_factory=list)
    resume_max_redirects: int = Field(default=3, ge=0, le=10)
    resume_download_timeout_seconds: float = Field(default=60.0, gt=0)

    @field_validator("resume_allowed_origins")
    @classmethod
    def normalize_resume_allowed_origins(cls, origins: list[str]) -> list[str]:
        """启动时规范化存储来源，避免运行时因错误白名单放宽下载边界。"""
        normalized_origins: list[str] = []
        for raw_origin in origins:
            parsed = urlparse(raw_origin.strip())
            hostname = (parsed.hostname or "").rstrip(".").lower()
            if (
                parsed.scheme not in {"http", "https"}
                or not hostname
                or parsed.username
                or parsed.password
                or parsed.path not in {"", "/"}
                or parsed.params
                or parsed.query
                or parsed.fragment
            ):
                raise ValueError(
                    "RESUME_ALLOWED_ORIGINS 必须是无路径、参数和用户信息的 HTTP(S) 来源"
                )

            try:
                port = parsed.port or (443 if parsed.scheme == "https" else 80)
            except ValueError as exc:
                raise ValueError("RESUME_ALLOWED_ORIGINS 包含无效端口") from exc

            host_for_origin = f"[{hostname}]" if ":" in hostname else hostname
            normalized_origins.append(f"{parsed.scheme}://{host_for_origin}:{port}")
        return normalized_origins

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
