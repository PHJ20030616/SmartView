"""LLM 调用可观测：把每次模型调用的场景、用量与耗时落到 MySQL 的 llm_call_log。

这是 FastAPI 侧**唯一**允许写入的数据库表。它是技术可观测表而非业务主表，
边界已在 AGENTS.md 登记（见 develop_plan/plan_1.1.md §5.4）。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import threading
from dataclasses import dataclass
from typing import Any

from sqlalchemy import Engine, text

from app.core.config import Settings, get_settings
from app.services.resume_vectorizer import build_mysql_engine

log = logging.getLogger(__name__)

# 进程级引擎单例：埋点发生在每次 LLM 调用上，逐次建引擎会带来无谓的连接开销。
# 测试通过 monkeypatch 覆盖该变量注入 SQLite 替身。
_cached_engine: Engine | Any | None = None
# 保护单例首次构建（埋点写入发生在 to_thread 工作线程中，可能并发进入）
_engine_lock = threading.Lock()


@dataclass(slots=True)
class LlmCallRecord:
    """一次 LLM 调用的埋点数据。

    刻意不包含 prompt 与响应全文：简历原文、用户回答属敏感数据，
    只保留 request_hash 与长度用于归因（plan_1.1 §5.1 隐私约束）。
    """

    scene: str
    provider: str
    model: str
    status: str
    latency_ms: int
    retry_attempt: int = 0
    trace_id: str | None = None
    biz_type: str | None = None
    biz_id: int | None = None
    prompt_key: str | None = None
    prompt_version: str | None = None
    request_hash: str | None = None
    request_chars: int | None = None
    temperature: float | None = None
    max_tokens: int | None = None
    token_input: int | None = None
    token_output: int | None = None
    token_total: int | None = None
    error_code: str | None = None
    error_message: str | None = None


def _canonical_message(message: dict[str, str]) -> str:
    """把单条消息序列化成稳定字符串：键顺序固定，中文不转义。"""
    return json.dumps(message, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def hash_messages(messages: list[dict[str, str]]) -> str:
    """对提示词做 sha256，用于判断"是否同一请求"。

    归一化两层差异，避免同一个业务请求被算成两个哈希：
    1. sort_keys 保证字典键顺序不影响结果；
    2. 按序列化结果对消息排序，使消息顺序不影响结果——同一业务请求的提示词由
       固定构造器生成，顺序只是实现细节，归因时不应被它区分开。
    ensure_ascii=False 保留中文原样，避免中文提示词因转义方式不同得到两个哈希。

    语义边界：该值表达"归一化后是否同一请求"，**不**用于检出提示词结构变化
    （那由 prompt_key / prompt_version 承担）。排序后的 json 数组保证编码是单射，
    不会出现两条不同消息集合得到同一哈希的情况。
    """
    canonical = json.dumps(
        sorted(_canonical_message(message) for message in messages),
        ensure_ascii=False,
        separators=(",", ":"),
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


_INSERT_SQL = text(
    """
    INSERT INTO llm_call_log (
        trace_id, scene, biz_type, biz_id, provider, model,
        prompt_key, prompt_version, request_hash, request_chars,
        temperature, max_tokens, token_input, token_output, token_total,
        latency_ms, status, error_code, error_message, retry_attempt
    ) VALUES (
        :trace_id, :scene, :biz_type, :biz_id, :provider, :model,
        :prompt_key, :prompt_version, :request_hash, :request_chars,
        :temperature, :max_tokens, :token_input, :token_output, :token_total,
        :latency_ms, :status, :error_code, :error_message, :retry_attempt
    )
    """
)


async def record_llm_call(
    record: LlmCallRecord,
    *,
    settings: Settings | None = None,
    engine: Engine | Any | None = None,
) -> None:
    """把一次调用写入 llm_call_log。

    三处刻意的设计取舍：

    1. 用 asyncio.to_thread 执行同步 SQLAlchemy 写入。SQLAlchemy 的 Core 引擎是同步的，
       直接在协程里调用会阻塞事件循环；相对秒级的模型调用耗时，一次插入可以忽略。
    2. 任何异常（库不可用、表不存在、网络抖动、配置缺失）都只记 warning 并返回，绝不向上抛。
       可观测性故障不得演变成业务故障——面试不能因为日志写不进就中断。
       因此开关判断也在 try 内：settings 缺省时 get_settings() 可能因配置不完整抛
       ValidationError，那同样属于"埋点失败"，不应波及主流程。
    3. 开关关闭时提前返回，保证"关闭埋点"是可验证的行为对照基线。
    """
    try:
        runtime_settings = settings or get_settings()
        if not runtime_settings.llm_log_enabled:
            return
        await asyncio.to_thread(_insert, record, runtime_settings, engine)
    except Exception:  # noqa: BLE001 - 埋点失败必须被吞掉，否则会波及面试主流程
        log.warning("LLM 调用日志写入失败，已忽略（不影响本次调用）", exc_info=True)


def _insert(record: LlmCallRecord, settings: Settings, engine: Engine | Any | None) -> None:
    """执行单条插入；error_message 按列宽截断，避免超长堆栈撑爆字段。"""
    params = {
        "trace_id": record.trace_id,
        "scene": record.scene,
        "biz_type": record.biz_type,
        "biz_id": record.biz_id,
        "provider": record.provider,
        "model": record.model,
        "prompt_key": record.prompt_key,
        "prompt_version": record.prompt_version,
        "request_hash": record.request_hash,
        "request_chars": record.request_chars,
        "temperature": record.temperature,
        "max_tokens": record.max_tokens,
        "token_input": record.token_input,
        "token_output": record.token_output,
        "token_total": record.token_total,
        "latency_ms": record.latency_ms,
        "status": record.status,
        "error_code": record.error_code,
        "error_message": record.error_message[:500] if record.error_message else None,
        "retry_attempt": record.retry_attempt,
    }
    with _resolve_engine(settings, engine).begin() as connection:
        connection.execute(_INSERT_SQL, params)


def _resolve_engine(settings: Settings, engine: Engine | Any | None) -> Engine | Any:
    """解析要使用的数据库引擎：优先显式传入，其次复用进程级单例。

    单例构建加锁：_insert 跑在 asyncio.to_thread 的工作线程里，并发首批埋点可能同时
    进入这里，无锁会建出两个连接池并泄漏其中一个。
    建连超时压到 5 秒：MySQL 不可用时埋点本就会失败，但不该让每次调用都多等驱动默认的 10 秒。
    """
    if engine is not None:
        return engine
    global _cached_engine
    if _cached_engine is None:
        with _engine_lock:
            if _cached_engine is None:
                _cached_engine = build_mysql_engine(settings, connect_timeout_seconds=5)
    return _cached_engine
