import asyncio
import socket
from datetime import datetime, timezone
from uuid import UUID

import fitz
import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.main import create_app
from app.schemas.resume import ParseResumeResponse
from app.services import resume_parser
from app.workers.resume_worker import process_resume_parse_task


def _make_pdf(text: str = "") -> bytes:
    document = fitz.open()
    page = document.new_page()
    if text:
        page.insert_text((72, 72), text)
    payload = document.tobytes()
    document.close()
    return payload


def _llm_payload(raw_text: str) -> dict:
    return {
        "candidateName": "张三",
        "contactInfo": {"phone": "13800138000", "email": "zhangsan@example.com"},
        "education": [{"school": "清华大学", "degree": "本科", "major": "计算机科学"}],
        "workExperience": [{"company": "示例科技", "position": "后端工程师"}],
        "projectExperience": [
            {
                "projectName": "招聘平台",
                "role": "核心开发",
                "description": "负责服务端开发",
                "techStack": ["Python", "FastAPI"],
            }
        ],
        "skills": ["Python", "FastAPI"],
        "rawText": raw_text,
    }


def _task_payload(*, retry_count: int = 0) -> dict:
    return {
        "taskId": "00000000-0000-0000-0000-000000000104",
        "traceId": "00000000-0000-0000-0000-000000000004",
        "messageType": "RESUME_PARSE_TASK",
        "schemaVersion": "1.0.0",
        "retryCount": retry_count,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "fileUrl": "https://minio.example.com/resume.pdf",
        "mimeType": "application/pdf",
        "resumeFileId": "resume-file-1",
    }


def test_text_pdf_returns_structured_resume(monkeypatch) -> None:
    pdf_bytes = _make_pdf("张三\n电话：13800138000\n技能：Python FastAPI")

    async def fake_download(state, settings):
        return {"pdf_bytes": pdf_bytes}

    async def fake_llm(raw_text, settings, trace_id, repair_error=None):
        payload = _llm_payload(raw_text)
        payload["rawText"] = "模型改写后的文本"
        return payload

    monkeypatch.setattr(resume_parser, "_download_pdf", fake_download)
    monkeypatch.setattr(resume_parser, "_call_deepseek", fake_llm)

    result = asyncio.run(
        resume_parser.parse_resume(
            "https://minio.example.com/resume.pdf",
            "application/pdf",
            "00000000-0000-0000-0000-000000000001",
            settings=Settings(_env_file=None),
        )
    )

    assert result.success is True
    assert result.candidateName == "张三"
    assert "13800138000" in result.rawText
    assert result.rawText != "模型改写后的文本"
    assert result.skills == ["Python", "FastAPI"]


def test_textless_pdf_uses_ocr_fallback(monkeypatch) -> None:
    pdf_bytes = _make_pdf()
    ocr_calls: list[int] = []

    async def fake_download(state, settings):
        return {"pdf_bytes": pdf_bytes}

    def fake_ocr(pdf, page_index, settings):
        ocr_calls.append(page_index)
        return "李四\n技能：Java"

    async def fake_llm(raw_text, settings, trace_id, repair_error=None):
        return _llm_payload(raw_text)

    monkeypatch.setattr(resume_parser, "_download_pdf", fake_download)
    monkeypatch.setattr(resume_parser, "_ocr_page", fake_ocr)
    monkeypatch.setattr(resume_parser, "_call_deepseek", fake_llm)

    result = asyncio.run(
        resume_parser.parse_resume(
            "https://minio.example.com/scanned.pdf",
            "application/pdf",
            "00000000-0000-0000-0000-000000000002",
            settings=Settings(_env_file=None, resume_min_useful_page_characters=20),
        )
    )

    assert result.success is True
    assert ocr_calls == [0]
    assert "李四" in result.rawText


def test_garbled_text_layer_uses_ocr_fallback() -> None:
    settings = Settings(_env_file=None, resume_min_useful_page_characters=20)

    assert resume_parser._useful_text("锟" * 30, settings) is False
    assert resume_parser._useful_text("Page 1 of 10 Resume Candidate Information", settings) is False
    assert resume_parser._useful_text("张三 Python 后端工程师 联系电话 13800138000", settings) is True


def test_resume_url_rejects_private_dns_result(monkeypatch) -> None:
    def fake_getaddrinfo(host, port, type):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 443))]

    monkeypatch.setattr(resume_parser.socket, "getaddrinfo", fake_getaddrinfo)

    with pytest.raises(AppError) as exc_info:
        resume_parser._validate_download_url(
            "https://files.example.com/resume.pdf",
            Settings(_env_file=None),
        )

    assert exc_info.value.code == "RESUME_URL_BLOCKED"


def test_resume_url_enforces_configured_storage_host() -> None:
    with pytest.raises(AppError) as exc_info:
        resume_parser._validate_download_url(
            "https://untrusted.example.com/resume.pdf",
            Settings(_env_file=None, resume_allowed_hosts=["minio.example.com"]),
        )

    assert exc_info.value.code == "RESUME_URL_NOT_ALLOWED"


def test_resume_url_allows_private_ip_for_configured_storage_host(monkeypatch) -> None:
    def fake_getaddrinfo(host, port, type):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", 9000))]

    monkeypatch.setattr(resume_parser.socket, "getaddrinfo", fake_getaddrinfo)

    result = resume_parser._validate_download_url(
        "http://minio.local:9000/resume.pdf",
        Settings(_env_file=None, resume_allowed_hosts=["minio.local"]),
    )

    assert result == "http://minio.local:9000/resume.pdf"


def test_resume_url_rejects_unlisted_plain_http_storage() -> None:
    with pytest.raises(AppError) as exc_info:
        resume_parser._parse_download_url(
            "http://files.example.com/resume.pdf",
            Settings(_env_file=None),
        )

    assert exc_info.value.code == "RESUME_INSECURE_URL"


def test_ocr_rejects_pages_that_would_exceed_render_budget() -> None:
    document = fitz.open()
    page = document.new_page(width=20000, height=20000)
    page.insert_text((72, 72), "简历")
    pdf_bytes = document.tobytes()
    document.close()

    with pytest.raises(AppError) as exc_info:
        resume_parser._render_page(
            pdf_bytes,
            0,
            200,
            Settings(_env_file=None, resume_max_page_dimension=10000),
        )

    assert exc_info.value.code == "RESUME_PAGE_TOO_LARGE"


def test_invalid_llm_json_is_repaired_once(monkeypatch) -> None:
    calls: list[str | None] = []

    async def fake_llm(raw_text, settings, trace_id, repair_error=None):
        calls.append(repair_error)
        if repair_error is None:
            raise AppError(
                "模型返回的简历 JSON 为空或格式无效",
                code="LLM_INVALID_JSON",
                status_code=502,
            )
        return _llm_payload(raw_text)

    monkeypatch.setattr(resume_parser, "_call_deepseek", fake_llm)
    result = asyncio.run(
        resume_parser._structure_resume(
            {
                "raw_text": "张三 Python 后端工程师",
                "trace_id": "00000000-0000-0000-0000-000000000099",
            },
            Settings(_env_file=None),
        )
    )

    assert result["result"].success is True
    assert len(calls) == 2
    assert calls[1]


def test_deepseek_limits_input_text_and_does_not_request_raw_text(monkeypatch) -> None:
    captured: dict = {}

    class FakeResponse:
        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return {"choices": [{"message": {"content": "{}"}}]}

    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return None

        async def post(self, path, headers, json):
            captured.update(json)
            return FakeResponse()

    monkeypatch.setattr(resume_parser.httpx, "AsyncClient", lambda **kwargs: FakeClient())
    asyncio.run(
        resume_parser._call_deepseek(
            "A" * 100,
            Settings(
                _env_file=None,
                deepseek_api_key="test-key",
                deepseek_max_input_characters=20,
            ),
            "trace-1",
        )
    )

    messages = captured["messages"]
    assert "A" * 20 in messages[1]["content"]
    assert "A" * 21 not in messages[1]["content"]
    assert "不需要在 JSON 中重复输出原文" in messages[1]["content"]


def test_parse_api_exposes_readable_failure(monkeypatch) -> None:
    async def fake_parse_resume(file_url, mime_type, trace_id):
        return ParseResumeResponse(
            success=False,
            rawText="",
            errorMessage="简历文件下载失败，请检查文件地址是否有效",
        )

    monkeypatch.setattr("app.api.v1.resume.parse_resume", fake_parse_resume)
    monkeypatch.setenv("AI_SERVICE_API_KEY", "test-service-key")
    get_settings.cache_clear()
    client = TestClient(create_app())
    response = client.post(
        "/api/v1/resume/parse",
        headers={"X-API-Key": "test-service-key"},
        json={
            "fileUrl": "https://minio.example.com/resume.pdf",
            "mimeType": "application/pdf",
            "traceId": "00000000-0000-0000-0000-000000000003",
        },
    )

    assert response.status_code == 200
    assert response.json()["success"] is False
    assert "下载失败" in response.json()["errorMessage"]
    get_settings.cache_clear()


def test_parse_api_rejects_invalid_api_key(monkeypatch) -> None:
    monkeypatch.setenv("AI_SERVICE_API_KEY", "expected-service-key")
    get_settings.cache_clear()
    client = TestClient(create_app())

    response = client.post(
        "/api/v1/resume/parse",
        headers={"X-API-Key": "wrong-key"},
        json={
            "fileUrl": "https://minio.example.com/resume.pdf",
            "mimeType": "application/pdf",
            "traceId": "00000000-0000-0000-0000-000000000005",
        },
    )

    assert response.status_code == 401
    assert response.json()["error"] == "AUTHENTICATION_FAILED"
    assert "鉴权失败" in response.json()["message"]
    get_settings.cache_clear()


def test_paddle_result_reader_supports_current_and_legacy_shapes() -> None:
    current = resume_parser._read_paddle_result(
        [{"rec_texts": ["张三", "Python"]}]
    )
    legacy = resume_parser._read_paddle_result(
        [[[[0, 0], ["李四", 0.99]]]]
    )

    assert current == "张三\nPython"
    assert legacy == "李四"


def test_ocr_dependency_error_is_not_hidden(monkeypatch) -> None:
    def fake_engine():
        raise AppError("未安装 PaddleOCR 依赖", code="OCR_DEPENDENCY_MISSING")

    monkeypatch.setattr(resume_parser, "_get_paddle_ocr", fake_engine)

    try:
        resume_parser._ocr_page(b"unused", 0, Settings(_env_file=None))
    except AppError as exc:
        assert exc.code == "OCR_DEPENDENCY_MISSING"
        assert "PaddleOCR" in exc.message
    else:
        raise AssertionError("OCR 依赖错误不应被吞掉")


def test_worker_reuses_parser_contract(monkeypatch) -> None:
    captured: dict[str, str] = {}

    async def fake_parse_resume(file_url, mime_type, trace_id, raise_on_error):
        captured.update(file_url=file_url, mime_type=mime_type, trace_id=trace_id)
        assert raise_on_error is True
        return ParseResumeResponse(success=True, rawText="测试简历")

    monkeypatch.setattr("app.workers.resume_worker.parse_resume", fake_parse_resume)
    result = asyncio.run(process_resume_parse_task(_task_payload()))

    assert result["success"] is True
    assert result["messageType"] == "RESUME_PARSE_RESULT"
    assert result["resumeFileId"] == "resume-file-1"
    assert result["taskId"] == "00000000-0000-0000-0000-000000000104"
    assert captured["trace_id"] == str(UUID("00000000-0000-0000-0000-000000000004"))


def test_worker_raises_retryable_error_before_last_attempt(monkeypatch) -> None:
    async def fake_parse_resume(file_url, mime_type, trace_id, raise_on_error):
        raise AppError("临时失败", code="LLM_REQUEST_FAILED", status_code=502)

    monkeypatch.setattr("app.workers.resume_worker.parse_resume", fake_parse_resume)

    with pytest.raises(AppError):
        asyncio.run(process_resume_parse_task(_task_payload(retry_count=2)))


def test_worker_returns_failure_result_after_last_attempt(monkeypatch) -> None:
    async def fake_parse_resume(file_url, mime_type, trace_id, raise_on_error):
        raise AppError("最终失败", code="LLM_REQUEST_FAILED", status_code=502)

    monkeypatch.setattr("app.workers.resume_worker.parse_resume", fake_parse_resume)
    result = asyncio.run(process_resume_parse_task(_task_payload(retry_count=3)))

    assert result["success"] is False
    assert result["errorMessage"] == "最终失败"
    assert result["messageType"] == "RESUME_PARSE_RESULT"
    assert "candidateName" not in result
