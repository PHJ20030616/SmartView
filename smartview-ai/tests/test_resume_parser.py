from pydantic import SecretStr

from app.core.config import Settings


def test_deepseek_settings_have_safe_defaults(monkeypatch) -> None:
    monkeypatch.setenv("DEEPSEEK_API_KEY", "test-key")
    monkeypatch.setenv("AI_SERVICE_API_KEY", "service-test-key")

    settings = Settings(_env_file=None)

    assert isinstance(settings.deepseek_api_key, SecretStr)
    assert settings.deepseek_api_key.get_secret_value() == "test-key"
    assert settings.ai_service_api_key.get_secret_value() == "service-test-key"
    assert settings.deepseek_base_url == "https://api.deepseek.com"
    assert settings.deepseek_model == "deepseek-chat"
