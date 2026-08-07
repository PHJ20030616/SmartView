"""DeepSeek LLM 调用客户端（JSON 模式），供各 AI 服务复用。

从 profile_analyzer 的私有调用中抽离为公共能力，首题生成、回答评估、
报告生成等需要 LLM JSON 输出的服务统一复用本模块，避免重复实现。
"""

from __future__ import annotations

import json
import logging
from typing import Any

import httpx

from app.core.config import Settings
from app.core.errors import AppError

log = logging.getLogger(__name__)


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
    what: str = "结果",
    repair_error: str | None = None,
) -> dict[str, Any]:
    """调用 DeepSeek JSON 模式；API Key 缺失时给出明确配置错误。

    repair_error 非空时在消息末尾追加修复指令，供调用方在校验失败后
    做一次带上下文的修复调用（如 LLM_INVALID_JSON / 字段校验失败）。
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
    log.info("调用 DeepSeek 生成%s repair=%s", what, bool(repair_error))
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
            content = body["choices"][0]["message"]["content"]
            parsed = parse_json_content(content, what=what)
            log.info("DeepSeek 生成%s成功", what)
            return parsed
    except AppError:
        raise
    except (httpx.HTTPError, KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        log.exception("DeepSeek 生成%s失败", what)
        raise AppError(
            "AI 生成服务暂时不可用，请稍后重试",
            code="LLM_REQUEST_FAILED",
            status_code=502,
        ) from exc
