"""LLM 调用埋点测试。

用 SQLite 内存库替身承载真实 SQL 写入（与 test_profile_analyzer 的引擎替身模式一致），
不依赖 MySQL：断言的是"写进去的字段对不对"与"失败时会不会拖垮主流程"这两件事。
"""
import asyncio

from sqlalchemy import create_engine, text
from sqlalchemy.pool import StaticPool

from app.core.config import Settings
from app.core.llm_context import (
    current_llm_context,
    reset_llm_context,
    set_llm_context,
)
from app.observability import llm_call_log
from app.observability.llm_call_log import (
    LlmCallRecord,
    hash_messages,
    record_llm_call,
)


def _sqlite_engine():
    """构造带 llm_call_log 表的 SQLite 内存引擎。

    必须用 StaticPool + check_same_thread=False：SQLite 的 `sqlite://` 默认按线程
    分配连接，而埋点走 asyncio.to_thread 在**工作线程**执行插入，主线程再读同一个
    内存库时会看到另一个空库。StaticPool 让全流程共用同一条连接，才是对真实写入的验证。
    """
    engine = create_engine(
        "sqlite://",
        poolclass=StaticPool,
        connect_args={"check_same_thread": False},
    )
    with engine.begin() as connection:
        connection.execute(
            text(
                """
                CREATE TABLE llm_call_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    trace_id VARCHAR(64),
                    scene VARCHAR(50) NOT NULL,
                    biz_type VARCHAR(50),
                    biz_id INTEGER,
                    provider VARCHAR(30) NOT NULL DEFAULT 'deepseek',
                    model VARCHAR(100) NOT NULL,
                    prompt_key VARCHAR(100),
                    prompt_version VARCHAR(50),
                    request_hash CHAR(64),
                    request_chars INTEGER,
                    temperature NUMERIC(3, 2),
                    max_tokens INTEGER,
                    token_input INTEGER,
                    token_output INTEGER,
                    token_total INTEGER,
                    latency_ms INTEGER NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    error_code VARCHAR(50),
                    error_message VARCHAR(500),
                    retry_attempt INTEGER NOT NULL DEFAULT 0,
                    http_status INTEGER,
                    finish_reason VARCHAR(30),
                    attempt_no INTEGER NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
        )
    return engine


def _record(**overrides) -> LlmCallRecord:
    base = LlmCallRecord(
        scene="evaluate",
        provider="deepseek",
        model="deepseek-v4-flash",
        status="SUCCESS",
        latency_ms=1234,
    )
    for key, value in overrides.items():
        setattr(base, key, value)
    return base


def test_record_is_persisted_with_all_dimensions(monkeypatch) -> None:
    engine = _sqlite_engine()
    monkeypatch.setattr(llm_call_log, "_cached_engine", engine)

    asyncio.run(
        record_llm_call(
            _record(
                trace_id="00000000-0000-0000-0000-0000000000aa",
                biz_type="interview_session",
                biz_id=14,
                prompt_key="evaluate.answer",
                prompt_version="p0",
                request_hash="a" * 64,
                request_chars=2048,
                token_input=100,
                token_output=200,
                token_total=300,
                retry_attempt=1,
                attempt_no=2,
                http_status=200,
                finish_reason="length",
            ),
            settings=Settings(_env_file=None),
        )
    )

    with engine.connect() as connection:
        row = connection.execute(text("SELECT * FROM llm_call_log")).mappings().one()

    assert row["scene"] == "evaluate"
    assert row["status"] == "SUCCESS"
    assert row["latency_ms"] == 1234
    assert row["trace_id"] == "00000000-0000-0000-0000-0000000000aa"
    assert row["token_total"] == 300
    assert row["retry_attempt"] == 1
    # 业务维度与截断诊断字段：这两组字段此前要么从未填充（biz_*），
    # 要么根本不存在（finish_reason），导致"哪个会话最贵""为何 JSON 无效"都答不出来
    assert row["biz_type"] == "interview_session"
    assert row["biz_id"] == 14
    assert row["prompt_key"] == "evaluate.answer"
    assert row["attempt_no"] == 2
    assert row["http_status"] == 200
    assert row["finish_reason"] == "length"


def test_llm_context_fills_business_dimensions(monkeypatch) -> None:
    """入口设置的业务上下文必须能被埋点读到，否则按会话归因成本无从谈起。"""
    engine = _sqlite_engine()
    monkeypatch.setattr(llm_call_log, "_cached_engine", engine)

    token = set_llm_context(biz_type="interview_session", biz_id="14", attempt_no="2")
    try:
        # 埋点读取上下文的是 deepseek_client._record_call；这里直接验证上下文本身可读，
        # 端到端链路（HTTP/MQ 入口 → 埋点字段）由 test_deepseek_client_logging 覆盖。
        context = current_llm_context()
        asyncio.run(
            record_llm_call(
                _record(
                    biz_type=context.biz_type,
                    biz_id=context.biz_id,
                    attempt_no=context.attempt_no,
                ),
                settings=Settings(_env_file=None),
            )
        )
    finally:
        reset_llm_context(token)

    with engine.connect() as connection:
        row = connection.execute(text("SELECT * FROM llm_call_log")).mappings().one()
    assert row["biz_type"] == "interview_session"
    assert row["biz_id"] == 14
    assert row["attempt_no"] == 2


def test_llm_context_normalizes_dirty_business_ids() -> None:
    """脏 ID 必须被静默归一，不能让埋点因为一个非法值把业务主流程拖挂。"""
    token = set_llm_context(biz_type="resume_file", biz_id="not-a-number", attempt_no=None)
    try:
        context = current_llm_context()
        assert context.biz_type == "resume_file"
        assert context.biz_id is None
        assert context.attempt_no == 0
    finally:
        reset_llm_context(token)
    # 恢复后必须回到空上下文，避免维度泄漏到同一线程的后续任务
    assert current_llm_context().biz_type is None


def test_disabled_switch_writes_nothing(monkeypatch) -> None:
    """开关关闭时必须与埋点引入前行为一致：不产生任何写入。"""
    engine = _sqlite_engine()
    monkeypatch.setattr(llm_call_log, "_cached_engine", engine)

    asyncio.run(
        record_llm_call(_record(), settings=Settings(_env_file=None, llm_log_enabled=False))
    )

    with engine.connect() as connection:
        count = connection.execute(text("SELECT COUNT(*) FROM llm_call_log")).scalar_one()

    assert count == 0


def test_storage_failure_is_swallowed(monkeypatch) -> None:
    """可观测性故障不得演变成业务故障：表不存在时也必须安静返回。"""
    monkeypatch.setattr(llm_call_log, "_cached_engine", create_engine("sqlite://"))

    # 不应抛出任何异常
    asyncio.run(record_llm_call(_record(), settings=Settings(_env_file=None)))


def test_hash_messages_is_stable_and_order_insensitive() -> None:
    first = [{"role": "system", "content": "你是面试官"}, {"role": "user", "content": "题目"}]
    second = [{"role": "user", "content": "题目"}, {"role": "system", "content": "你是面试官"}]

    assert hash_messages(first) == hash_messages(second)
    assert len(hash_messages(first)) == 64
    # 归因用的哈希不能反过来推出原文
    assert "你是面试官" not in hash_messages(first)
