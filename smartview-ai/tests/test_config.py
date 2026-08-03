"""共享凭据加载与 Settings 解析测试。

验证 smartview-infra/.env 作为唯一事实来源被注入环境变量，
以及 Settings 能同时解析共享凭据与 smartview-ai/.env 的 Python 专属配置。
"""

from __future__ import annotations

import os
from pathlib import Path

from app.core.config import (
    Settings,
    _load_shared_infra_env,
    _resolve_infra_env_path,
)


def test_resolve_infra_env_path_default_points_to_repo_infra(monkeypatch) -> None:
    """未设置 SMARTVIEW_INFRA_ENV_FILE 时，默认指向仓库根的 smartview-infra/.env。"""
    monkeypatch.delenv("SMARTVIEW_INFRA_ENV_FILE", raising=False)

    path = Path(_resolve_infra_env_path())

    assert path.name == ".env"
    assert path.parent.name == "smartview-infra"
    # smartview-infra 目录本身是跟踪的，全新检出也存在；
    # 不断言 .env 存在，避免测试依赖被 gitignore 忽略的敏感文件
    assert path.parent.is_dir()


def test_resolve_infra_env_path_respects_env_override(monkeypatch) -> None:
    """SMARTVIEW_INFRA_ENV_FILE 可以覆盖默认路径（Docker 场景）。"""
    monkeypatch.setenv("SMARTVIEW_INFRA_ENV_FILE", "/custom/infra.env")

    assert _resolve_infra_env_path() == "/custom/infra.env"


def test_load_shared_infra_env_injects_credentials(monkeypatch, tmp_path) -> None:
    """infra .env 中的共享凭据应被注入环境变量。"""
    infra_file = tmp_path / "infra.env"
    infra_file.write_text(
        "MYSQL_HOST=dbhost\nMYSQL_PORT=3307\nMYSQL_PASSWORD=secret\n",
        encoding="utf-8",
    )
    # 清理可能已由真实 infra .env 注入的键，确保断言只针对本次临时加载
    for key in ("MYSQL_HOST", "MYSQL_PORT", "MYSQL_PASSWORD"):
        monkeypatch.delenv(key, raising=False)

    _load_shared_infra_env(str(infra_file))

    assert os.environ["MYSQL_HOST"] == "dbhost"
    assert os.environ["MYSQL_PORT"] == "3307"
    assert os.environ["MYSQL_PASSWORD"] == "secret"


def test_load_shared_infra_env_does_not_override_existing_env(
    monkeypatch, tmp_path
) -> None:
    """进程里已有的真实环境变量优先，infra .env 不应覆盖。"""
    infra_file = tmp_path / "infra.env"
    infra_file.write_text("MYSQL_HOST=dbhost\n", encoding="utf-8")
    monkeypatch.setenv("MYSQL_HOST", "real-host")

    _load_shared_infra_env(str(infra_file))

    assert os.environ["MYSQL_HOST"] == "real-host"


def test_load_shared_infra_env_ignores_missing_file() -> None:
    """infra .env 不存在时应静默跳过，不抛异常。"""
    _load_shared_infra_env(str(Path("/nonexistent/infra.env")))


def test_settings_reads_shared_credentials_from_env(monkeypatch, tmp_path) -> None:
    """Settings 应从注入的共享环境变量解析 MySQL / RabbitMQ 凭据。"""
    infra_file = tmp_path / "infra.env"
    infra_file.write_text(
        "MYSQL_HOST=dbhost\nMYSQL_PORT=3307\nMYSQL_USERNAME=root\n"
        "RABBITMQ_DEFAULT_USER=rabbituser\nRABBITMQ_DEFAULT_PASS=pw\n",
        encoding="utf-8",
    )
    for key in (
        "MYSQL_HOST",
        "MYSQL_PORT",
        "MYSQL_USERNAME",
        "RABBITMQ_DEFAULT_USER",
        "RABBITMQ_DEFAULT_PASS",
    ):
        monkeypatch.delenv(key, raising=False)

    _load_shared_infra_env(str(infra_file))
    settings = Settings(_env_file=None)

    assert settings.mysql_host == "dbhost"
    assert settings.mysql_port == 3307
    assert settings.mysql_username == "root"
    assert settings.rabbitmq_username == "rabbituser"
    assert settings.rabbitmq_password.get_secret_value() == "pw"


def test_settings_reads_python_specific_from_dotenv(tmp_path) -> None:
    """Python 专属字段仍从 smartview-ai/.env（dotenv）读取。"""
    ai_env = tmp_path / "ai.env"
    ai_env.write_text(
        "DEEPSEEK_MODEL=test-model\nCHROMA_PERSIST_DIRECTORY=./tmp-chroma\n",
        encoding="utf-8",
    )

    settings = Settings(_env_file=str(ai_env))

    assert settings.deepseek_model == "test-model"
    assert settings.chroma_persist_directory == "./tmp-chroma"
