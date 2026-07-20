"""
日志配置模块

配置应用的日志系统，使用标准的 Python logging 模块。
"""
import logging
from logging.config import dictConfig


def configure_logging(log_level: str) -> None:
    """
    配置应用日志系统

    设置日志格式、处理器和日志级别。
    日志输出到控制台，格式包含时间戳、级别、日志器名称和消息。

    参数:
        log_level: 日志级别（DEBUG, INFO, WARNING, ERROR, CRITICAL）
    """
    normalized_level = log_level.upper()
    dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {
                "default": {
                    "format": "%(asctime)s %(levelname)s [%(name)s] %(message)s",
                }
            },
            "handlers": {
                "console": {
                    "class": "logging.StreamHandler",
                    "formatter": "default",
                    "level": normalized_level,
                }
            },
            "root": {
                "handlers": ["console"],
                "level": normalized_level,
            },
        }
    )
    # 配置 Uvicorn 访问日志级别
    logging.getLogger("uvicorn.access").setLevel(normalized_level)
