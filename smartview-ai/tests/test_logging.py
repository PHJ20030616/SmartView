"""日志系统测试：验证日志配置、traceId 注入与请求级访问日志。"""
import logging
from logging.handlers import RotatingFileHandler

from fastapi.testclient import TestClient

from app.core.logging import TraceIdFilter, configure_logging
from app.core.trace import reset_trace_id, set_trace_id
from app.main import create_app


class RecordCollector(logging.Handler):
    """自定义日志收集器。

    不依赖 pytest caplog：create_app() 中的 dictConfig 会整体替换 root 的
    handlers，导致挂到 root 上的 caplog handler 失效。
    """

    def __init__(self) -> None:
        super().__init__()
        self.records: list[logging.LogRecord] = []

    def emit(self, record: logging.LogRecord) -> None:
        # 模拟生产 handler：在发出时应用 TraceIdFilter，把当时的 trace_id 固化到记录上
        TraceIdFilter().filter(record)
        self.records.append(record)


def _collect_access_logs():
    """在 smartview.access logger 上挂收集器，返回 (collector, logger)。"""
    collector = RecordCollector()
    logger = logging.getLogger("smartview.access")
    logger.addHandler(collector)
    return collector, logger


def _format_with_trace_id(records):
    """按生产日志格式渲染记录，读取发出时已固化的 trace_id 字段。"""
    formatter = logging.Formatter("%(trace_id)s %(message)s")
    return [formatter.format(record) for record in records]


def test_configure_logging_console_only() -> None:
    """控制台输出始终存在，且关闭 uvicorn 自带访问日志避免重复。"""
    configure_logging("INFO", log_file_enabled=False)

    root = logging.getLogger()
    assert any(
        isinstance(handler, logging.StreamHandler)
        and not isinstance(handler, logging.FileHandler)
        for handler in root.handlers
    )
    assert logging.getLogger("uvicorn.access").disabled is True


def test_configure_logging_rotating_file_handler(tmp_path) -> None:
    """启用文件日志时创建轮转文件处理器并生成日志文件。"""
    log_dir = tmp_path / "logs"
    log_file = log_dir / "app.log"
    configure_logging(
        "INFO",
        log_dir=str(log_dir),
        log_file_enabled=True,
        log_file_max_bytes=1024,
        log_file_backup_count=2,
        log_file_name="app.log",
    )

    assert log_file.exists()
    root = logging.getLogger()
    assert any(
        isinstance(handler, RotatingFileHandler)
        and handler.baseFilename == str(log_file)
        for handler in root.handlers
    )


def test_trace_id_filter_injects_context_value() -> None:
    """traceId 过滤器：有上下文时注入真实值，无上下文时使用占位符。"""
    record = logging.LogRecord("test", logging.INFO, __file__, 1, "msg", (), None)

    assert TraceIdFilter().filter(record) is True
    assert record.trace_id == "-"

    token = set_trace_id("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    try:
        assert TraceIdFilter().filter(record) is True
        assert record.trace_id == "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    finally:
        reset_trace_id(token)


def test_http_request_logs_include_trace_id() -> None:
    """HTTP 请求日志包含收到请求/处理完成两条记录，且携带 traceId 与状态码。"""
    app = create_app()
    collector, logger = _collect_access_logs()
    try:
        trace_id = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        response = TestClient(app).get(
            "/api/v1/health",
            headers={"X-Trace-Id": trace_id},
        )
    finally:
        logger.removeHandler(collector)

    assert response.status_code == 200
    assert response.headers["X-Trace-Id"] == trace_id
    messages = _format_with_trace_id(collector.records)
    assert any(f"{trace_id} 收到请求" in message for message in messages)
    assert any(
        f"{trace_id} 请求处理完成" in message and "status=200" in message
        for message in messages
    )


def test_http_error_request_still_logs_completion() -> None:
    """业务异常（如鉴权失败）也要输出请求完成日志，便于排查调用失败。"""
    app = create_app()
    collector, logger = _collect_access_logs()
    try:
        response = TestClient(app).post(
            "/api/v1/resume/parse",
            headers={"X-API-Key": "wrong-key"},
            json={
                "fileUrl": "https://minio.example.com/resume.pdf",
                "mimeType": "application/pdf",
                "traceId": "00000000-0000-0000-0000-000000000001",
            },
        )
    finally:
        logger.removeHandler(collector)

    assert response.status_code == 401
    messages = _format_with_trace_id(collector.records)
    assert any(
        "收到请求" in message and "path=/api/v1/resume/parse" in message
        for message in messages
    )
    assert any(
        "请求处理完成" in message and "status=401" in message
        for message in messages
    )

def test_unhandled_exception_logs_carry_trace_id() -> None:
    """未捕获异常时，中间件与全局兜底 handler 的日志都要携带 traceId。"""
    app = create_app()

    @app.get("/boom")
    def boom() -> None:
        raise RuntimeError("模拟未预期异常")

    access_collector, access_logger = _collect_access_logs()
    errors_collector = RecordCollector()
    errors_logger = logging.getLogger("app.core.errors")
    errors_logger.addHandler(errors_collector)
    try:
        trace_id = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        response = TestClient(app, raise_server_exceptions=False).get(
            "/boom",
            headers={"X-Trace-Id": trace_id},
        )
    finally:
        access_logger.removeHandler(access_collector)
        errors_logger.removeHandler(errors_collector)

    assert response.status_code == 500
    # ServerErrorMiddleware 兜底响应不经过 trace 中间件，由错误处理器手动回写响应头
    assert response.headers["X-Trace-Id"] == trace_id
    access_messages = _format_with_trace_id(access_collector.records)
    error_messages = _format_with_trace_id(errors_collector.records)
    assert any(f"{trace_id} 请求处理异常" in message for message in access_messages)
    assert any(
        f"{trace_id} Unhandled AI service exception" in message
        for message in error_messages
    )


def test_trace_context_restored_after_request() -> None:
    """请求结束后 traceId 上下文应恢复为空，避免泄漏到后续请求或任务。"""
    from app.core.trace import TRACE_ID_CONTEXT

    app = create_app()
    with TestClient(app) as client:
        client.get(
            "/api/v1/health",
            headers={"X-Trace-Id": "f47ac10b-58cc-4372-a567-0e02b2c3d479"},
        )

    assert TRACE_ID_CONTEXT.get() == ""
