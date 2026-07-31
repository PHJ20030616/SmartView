"""
日志配置模块

配置应用的日志系统，使用标准的 Python logging 模块。
输出目标：控制台 + 可选的轮转日志文件；每条日志自动携带当前请求/任务的追踪 ID。
"""
import logging
import os
from logging.config import dictConfig
from logging.handlers import RotatingFileHandler

from app.core.trace import TRACE_ID_CONTEXT

# 默认日志文件名（各进程可通过 log_file_name 参数覆盖为独立文件，避免多进程写同一个文件）
DEFAULT_LOG_FILE_NAME = "smartview.log"


class TraceIdFilter(logging.Filter):
    """日志过滤器：把当前上下文中的追踪 ID 附加到每条日志记录上。"""

    def filter(self, record: logging.LogRecord) -> bool:
        # 无追踪 ID 时显示占位符 "-"，保证日志格式字段始终存在
        record.trace_id = TRACE_ID_CONTEXT.get() or "-"
        return True


def configure_logging(
    log_level: str,
    *,
    log_dir: str = "./logs",
    log_file_enabled: bool = True,
    log_file_max_bytes: int = 50 * 1024 * 1024,
    log_file_backup_count: int = 7,
    log_file_name: str = DEFAULT_LOG_FILE_NAME,
) -> None:
    """
    配置应用日志系统

    设置日志格式、处理器和日志级别。日志同时输出到控制台和（可选的）轮转日志文件，
    格式统一携带 trace_id 字段，便于与 Spring Boot 的 X-Trace-Id 关联排查链路。

    参数:
        log_level: 日志级别（DEBUG, INFO, WARNING, ERROR, CRITICAL）
        log_dir: 日志文件输出目录
        log_file_enabled: 是否启用文件日志
        log_file_max_bytes: 单个日志文件大小上限（超出后轮转）
        log_file_backup_count: 保留的轮转备份文件数量
        log_file_name: 日志文件名，各进程应使用独立文件名
    """
    normalized_level = log_level.upper()
    handlers: dict[str, dict[str, object]] = {
        "console": {
            "class": "logging.StreamHandler",
            "formatter": "default",
            "filters": ["trace_id"],
            "level": normalized_level,
        }
    }
    if log_file_enabled:
        # 确保日志目录存在，避免 RotatingFileHandler 因目录缺失导致启动失败
        # 多进程部署（uvicorn --workers N）时多进程写同一文件轮转不安全，
        # 如需多进程请按 pid 拆分文件名或改用并发安全轮转实现。
        os.makedirs(log_dir, exist_ok=True)
        handlers["file"] = {
            "class": "logging.handlers.RotatingFileHandler",
            "filename": os.path.join(log_dir, log_file_name),
            "maxBytes": log_file_max_bytes,
            "backupCount": log_file_backup_count,
            "encoding": "utf-8",
            "formatter": "default",
            "filters": ["trace_id"],
            "level": normalized_level,
        }

    dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {
                "default": {
                    "format": (
                        "%(asctime)s %(levelname)s [%(name)s] "
                        "trace_id=%(trace_id)s %(message)s"
                    ),
                }
            },
            "filters": {
                "trace_id": {"()": TraceIdFilter},
            },
            "handlers": handlers,
            "root": {
                "handlers": list(handlers),
                "level": normalized_level,
            },
        }
    )
    # 请求级访问日志由 app/core/trace.py 的中间件输出（包含 traceId 与耗时），
    # 因此禁用 uvicorn 自带的访问日志，避免同一请求重复打印两条访问日志。
    # 注意：uvicorn 的 dictConfig 会重置日志器的 disabled 标志，本设置仅在
    # "uvicorn 先配置日志、后导入应用"（python -m uvicorn app.main:app）时生效；
    # 若在同一进程以 uvicorn.run(app 对象) 形式启动，请改用 --no-access-log。
    logging.getLogger("uvicorn.access").disabled = True
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
