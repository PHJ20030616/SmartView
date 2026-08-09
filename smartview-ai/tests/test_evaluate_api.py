"""POST /api/v1/interview/evaluate 端点测试（校验鉴权与路由）。"""
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import create_app

KEY = "test-service-key"


def test_evaluate_rejects_invalid_api_key(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_API_KEY", "expected-service-key")
    get_settings.cache_clear()
    client = TestClient(create_app())

    resp = client.post(
        "/api/v1/interview/evaluate",
        headers={"X-API-Key": "wrong-key"},
        json={"sessionId": "1"},
    )
    assert resp.status_code == 401
    get_settings.cache_clear()


def test_evaluate_validation_error_without_required_fields(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_API_KEY", KEY)
    get_settings.cache_clear()
    client = TestClient(create_app())

    resp = client.post(
        "/api/v1/interview/evaluate",
        headers={"X-API-Key": KEY},
        json={},
    )
    assert resp.status_code == 422
    get_settings.cache_clear()
