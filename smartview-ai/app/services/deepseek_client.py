"""DeepSeek LLM 调用客户端（JSON 模式），供各 AI 服务复用。

从 profile_analyzer 的私有调用中抽离为公共能力，首题生成、回答评估、
报告生成等需要 LLM JSON 输出的服务统一复用本模块，避免重复实现。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

import httpx

from app.core.config import Settings
from app.core.errors import AppError
from app.core.trace import current_trace_id
from app.observability.llm_call_log import LlmCallRecord, hash_messages, record_llm_call

log = logging.getLogger(__name__)

# 模型提供方标识：写死而非从 base_url 推断，避免以后换代理地址时历史数据被切成两类。
_PROVIDER_NAME = "deepseek"


def parse_json_content(content: Any, *, what: str = "结果") -> dict[str, Any]:
    """解析模型返回的 JSON；兼容 ```json 代码围栏，并禁止非对象结果。

    参数 what 用于错误消息中的业务命名（如"首题"），便于定位问题环节。
    """
    if not isinstance(content, str) or not content.strip():
        raise AppError(
            f"模型返回的{what} JSON 为空或格式无效",
            code="LLM_INVALID_JSON",
            status_code=502,
        )
    cleaned = content.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`")
        if cleaned.startswith("json"):
            cleaned = cleaned[4:].lstrip()
    try:
        value = json.loads(cleaned)
    except json.JSONDecodeError:
        # 兜底：提取首个 { 到末尾 } 之间的内容再解析一次，容忍多余前后缀文本。
        start, end = cleaned.find("{"), cleaned.rfind("}")
        if start < 0 or end <= start:
            raise AppError(
                f"模型返回的{what} JSON 格式无效",
                code="LLM_INVALID_JSON",
                status_code=502,
            )
        try:
            value = json.loads(cleaned[start : end + 1])
        except json.JSONDecodeError as exc:
            raise AppError(
                f"模型返回的{what} JSON 格式无效",
                code="LLM_INVALID_JSON",
                status_code=502,
            ) from exc
    if not isinstance(value, dict):
        raise AppError(
            f"模型返回的{what}不是 JSON 对象",
            code="LLM_INVALID_JSON",
            status_code=502,
        )
    return value


async def call_deepseek_json(
    messages: list[dict[str, str]],
    settings: Settings,
    *,
    scene: str,
    what: str = "结果",
    repair_error: str | None = None,
) -> dict[str, Any]:
    """调用 DeepSeek JSON 模式；API Key 缺失时给出明确配置错误。

    scene 是机器可读的调用场景标识（question_generate / evaluate / report_generate /
    profile_analyze / resume_parse），与面向用户的中文 what 分离：what 只用于错误文案，
    scene 用于埋点归因与看板筛选。刻意设为必填——新增调用点时忘记登记场景会直接报错，
    而不是静默产生一条无法归类的观测数据。

    repair_error 非空时在消息末尾追加修复指令，供调用方在校验失败后
    做一次带上下文的修复调用（如 LLM_INVALID_JSON / 字段校验失败）。

    本函数是全部 LLM 调用的唯一入口，也是唯一埋点位置（plan_1.1 §5.2/§5.3）。
    """
    api_key = settings.deepseek_api_key.get_secret_value().strip()
    if not api_key:
        raise AppError(
            "未配置 DeepSeek API Key，请检查 .env 配置",
            code="LLM_CONFIG_MISSING",
            status_code=503,
        )
    prompt_messages = list(messages)
    if repair_error:
        prompt_messages.append(
            {
                "role": "user",
                "content": f"上一次 JSON 校验失败，错误是：{repair_error}。"
                "请重新输出符合字段要求的 JSON。",
            }
        )
    payload = {
        "model": settings.deepseek_model,
        "messages": prompt_messages,
        "temperature": settings.deepseek_temperature,
        "max_tokens": settings.deepseek_max_tokens,
        "response_format": {"type": "json_object"},
    }
    log.info("调用 DeepSeek 生成%s scene=%s repair=%s", what, scene, bool(repair_error))
    started = time.perf_counter()
    # usage 由响应体的 usage 字段填充；失败路径拿不到用量，因此默认空字典。
    usage: dict[str, Any] = {}
    try:
        async with httpx.AsyncClient(
            base_url=settings.deepseek_base_url.rstrip("/"),
            timeout=settings.deepseek_timeout_seconds,
        ) as client:
            response = await client.post(
                "/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json=payload,
            )
            response.raise_for_status()
            body = response.json()
            # usage 是 OpenAI 兼容协议的可选字段；缺失时保持空，不因此判定调用失败。
            usage = body.get("usage") or {}
            content = body["choices"][0]["message"]["content"]
            parsed = parse_json_content(content, what=what)
    except AppError as exc:
        # 业务侧可识别的失败（JSON 为空/格式无效等），错误码原样落库便于按原因聚合。
        await _record_call(
            messages,
            settings,
            scene=scene,
            started=started,
            repair_error=repair_error,
            error_code=exc.code,
            error_message=exc.message,
        )
        raise
    except (httpx.HTTPError, KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        log.exception("DeepSeek 生成%s失败", what)
        error_message = "AI 生成服务暂时不可用，请稍后重试"
        await _record_call(
            messages,
            settings,
            scene=scene,
            started=started,
            repair_error=repair_error,
            error_code="LLM_REQUEST_FAILED",
            error_message=error_message,
        )
        raise AppError(
            error_message,
            code="LLM_REQUEST_FAILED",
            status_code=502,
        ) from exc

    await _record_call(
        messages,
        settings,
        scene=scene,
        started=started,
        repair_error=repair_error,
        usage=usage,
    )
    log.info(
        "DeepSeek 生成%s成功 scene=%s latency_ms=%s",
        what,
        scene,
        int((time.perf_counter() - started) * 1000),
    )
    return parsed


async def _record_call(
    messages: list[dict[str, str]],
    settings: Settings,
    *,
    scene: str,
    started: float,
    repair_error: str | None,
    usage: dict[str, Any] | None = None,
    error_code: str | None = None,
    error_message: str | None = None,
) -> None:
    """组装并写入一条调用记录，字段全部取自本次调用的实际观测值。

    注意这里调用的是 messages（未追加修复指令的原始提示词）：request_hash 表示
    "同一个业务请求"，修复调用与首次调用应当同哈希，靠 retry_attempt 区分，
    这样"修复率"才是一个可统计的量。
    """
    usage = usage or {}
    record = LlmCallRecord(
        scene=scene,
        provider=_PROVIDER_NAME,
        model=settings.deepseek_model,
        status="FAILED" if error_code else "SUCCESS",
        latency_ms=int((time.perf_counter() - started) * 1000),
        retry_attempt=1 if repair_error else 0,
        trace_id=current_trace_id(),
        prompt_version=settings.llm_prompt_version,
        request_hash=hash_messages(messages),
        request_chars=sum(len(message.get("content") or "") for message in messages),
        temperature=settings.deepseek_temperature,
        max_tokens=settings.deepseek_max_tokens,
        token_input=usage.get("prompt_tokens"),
        token_output=usage.get("completion_tokens"),
        token_total=usage.get("total_tokens"),
        error_code=error_code,
        error_message=error_message,
    )
    await record_llm_call(record, settings=settings)
