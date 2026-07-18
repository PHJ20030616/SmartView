from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.testclient import TestClient
import yaml

from app.core.errors import AppError
from app.main import app, create_app

client = TestClient(app)


def test_health_returns_success() -> None:
    response = client.get("/api/v1/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert "timestamp" in body


def test_docs_is_available() -> None:
    response = client.get("/docs")

    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]


def test_health_openapi_matches_contract_shape() -> None:
    schema = client.get("/openapi.json").json()
    operation = schema["paths"]["/api/v1/health"]["get"]
    health_schema = schema["components"]["schemas"]["HealthResponse"]

    assert operation["operationId"] == "aiHealthCheck"
    assert health_schema["required"] == ["status"]
    assert health_schema["properties"]["status"]["enum"] == ["UP", "DOWN"]
    assert health_schema["properties"]["timestamp"]["format"] == "date-time"
    assert health_schema["properties"]["timestamp"]["type"] == "string"


def test_health_returns_trace_id_header() -> None:
    trace_id = "f47ac10b-58cc-4372-a567-0e02b2c3d479"

    response = client.get("/api/v1/health", headers={"X-Trace-Id": trace_id})

    assert response.headers["X-Trace-Id"] == trace_id


def test_invalid_trace_id_is_replaced_with_uuid() -> None:
    response = client.get("/api/v1/health", headers={"X-Trace-Id": "invalid-trace-id"})

    assert response.headers["X-Trace-Id"] != "invalid-trace-id"
    assert len(response.headers["X-Trace-Id"]) == 36


def test_errors_match_contract_and_include_trace_id() -> None:
    test_app = create_app()

    @test_app.get("/test-error")
    def raise_error(request: Request) -> None:
        raise AppError("演示错误", code="DEMO_ERROR")

    response = TestClient(test_app).get(
        "/test-error",
        headers={"X-Trace-Id": "f47ac10b-58cc-4372-a567-0e02b2c3d479"},
    )

    assert response.status_code == 400
    assert response.json() == {
        "error": "DEMO_ERROR",
        "message": "演示错误",
        "traceId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    }
    assert response.headers["X-Trace-Id"] == response.json()["traceId"]


def test_error_contract_requires_trace_id() -> None:
    contract_path = Path(__file__).parents[2] / "contracts" / "ai-api" / "openapi.yaml"
    contract = yaml.safe_load(contract_path.read_text(encoding="utf-8"))

    for response_name in ("BadRequest", "InternalServerError"):
        required_fields = contract["components"]["responses"][response_name]["content"]["application/json"][
            "schema"
        ]["required"]
        assert "traceId" in required_fields
