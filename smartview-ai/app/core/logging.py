import logging
from logging.config import dictConfig


def configure_logging(log_level: str) -> None:
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
    logging.getLogger("uvicorn.access").setLevel(normalized_level)
