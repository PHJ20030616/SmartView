import pytest
from pydantic import SecretStr

from app.core.config import Settings
from app.core.errors import AppError
from app.services.resume_parser import _validate_download_url


def test_deepseek_settings_have_safe_defaults(monkeypatch) -> None:
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-key")
    monkeypatch.setenv("AI_SERVICE_API_KEY", "service-test-key")

    settings = Settings(_env_file=None)

    assert isinstance(settings.deepseek_api_key, SecretStr)
    assert settings.deepseek_api_key.get_secret_value() == "test-key"
    assert settings.ai_service_api_key.get_secret_value() == "service-test-key"
    assert settings.deepseek_base_url == "https://api.deepseek.com"
    assert settings.deepseek_model == "deepseek-v4-flash"


def test_local_minio_presigned_url_requires_explicit_allowlist() -> None:
    """本地开发的 MinIO 预签名地址只能在显式白名单后访问，避免放宽 SSRF 防护。"""
    file_url = "http://localhost:9000/smartview/resumes/8/resume.pdf?X-Amz-Signature=test"

    allowed_settings = Settings(
        _env_file=None,
        resume_allowed_origins=["http://localhost:9000"],
    )
    assert _validate_download_url(file_url, allowed_settings) == file_url

    blocked_settings = Settings(
        _env_file=None,
        resume_allowed_origins=["http://minio.example.com:9000"],
    )
    with pytest.raises(AppError, match="不在允许的存储地址范围内"):
        _validate_download_url(file_url, blocked_settings)

    wrong_port_url = file_url.replace(":9000", ":9001")
    with pytest.raises(AppError, match="不在允许的存储地址范围内"):
        _validate_download_url(wrong_port_url, allowed_settings)

    subdomain_url = file_url.replace("localhost", "minio.localhost")
    with pytest.raises(AppError, match="不在允许的存储地址范围内"):
        _validate_download_url(subdomain_url, allowed_settings)

    multi_origin_settings = Settings(
        _env_file=None,
        resume_allowed_origins=[
            "http://localhost:9000",
            "https://minio.example.com:443",
        ],
    )
    cross_origin_url = file_url.replace("http://localhost:9000", "http://localhost:443")
    with pytest.raises(AppError, match="不在允许的存储地址范围内"):
        _validate_download_url(cross_origin_url, multi_origin_settings)
