"""候选池生成 HTTP 端点测试。

覆盖：接口鉴权拦截、请求参数校验失败。
"""

from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.main import create_app

KEY = "test-service-key"


def test_candidate_pool_rejects_invalid_api_key(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_API_KEY", "expected-service-key")
    get_settings.cache_clear()
    client = TestClient(create_app())

    resp = client.post(
        "/api/v1/interview/candidate-pool",
        headers={"X-API-Key": "wrong-key"},
        json={"sessionId": "1"},
    )
    assert resp.status_code == 401
    get_settings.cache_clear()


def test_candidate_pool_validation_error_without_required_fields(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_API_KEY", KEY)
    get_settings.cache_clear()
    client = TestClient(create_app())

    resp = client.post(
        "/api/v1/interview/candidate-pool",
        headers={"X-API-Key": KEY},
        json={},
    )
    assert resp.status_code == 422
    get_settings.cache_clear()
