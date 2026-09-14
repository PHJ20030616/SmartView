"""DeepSeek LLM 调用客户端（JSON 模式），供各 AI 服务复用。

从 profile_analyzer 的私有调用中抽离为公共能力，首题生成、回答评估、
报告生成等需要 LLM JSON 输出的服务统一复用本模块，避免重复实现。
"""

from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any
from uuid import uuid4

import httpx

from app.core.config import Settings
from app.core.errors import AppError
from app.core.llm_context import current_llm_context
from app.core.trace import current_trace_id
from app.observability.llm_call_log import LlmCallRecord, hash_messages, record_llm_call

log = logging.getLogger(__name__)

# 客户端自报身份：OpenCode Zen/Go 等网关明确要求客户端用自己的名字标识，
# 而不是 httpx / SDK 的默认 UA（见 https://opencode.ai/docs/go/ 的 Validated Clients）。
_USER_AGENT = "smartview-ai/0.1.0"

# 无链路上下文时（本地脚本、定时任务）使用的兜底会话标识前缀。
# 后面会拼上进程级随机后缀：固定字符串会让所有离线任务挤进同一个网关会话，
# 与"按会话稳定、跨会话隔离"的语义相反。
_DEFAULT_SESSION_PREFIX = "smartview-ai-offline"

# 进程级共享 HTTP 客户端：此前每次调用都新建 AsyncClient，等于每次都要重做
# DNS + TCP + TLS 握手（单次 100~300ms，141 次调用全都如此）。按
# (base_url, timeout) 维度复用即可，运维换网关时旧客户端自然不再被命中。
# 单例只在进程内共享：AsyncClient 绑定事件循环，跨循环复用会报错，而生产环境
# 每个进程只有一个事件循环（uvicorn 与各 worker 各起一个）。
_SHARED_CLIENTS: dict[tuple[str, float], httpx.AsyncClient] = {}
_CLIENT_LOCK = threading.Lock()

# 连接池上限：与并发上限同量级即可，留出余量避免并发调用互等连接。
_CLIENT_MAX_CONNECTIONS = 16
_CLIENT_MAX_KEEPALIVE = 8

# 进程级离线会话标识：同一进程内的离线调用共享一个会话（保留提示词缓存收益），
# 不同进程彼此隔离。用 uuid4 而不是时间戳，避免同一毫秒内启动的进程撞号。
_OFFLINE_SESSION_ID = f"{_DEFAULT_SESSION_PREFIX}-{uuid4()}"

# HTTP 状态码 → 内部错误码。分三类的目的是让重试策略能按"确定性/瞬时"决策：
# 429 与 5xx 是上游瞬时状态，退避重试有意义；其余 4xx 是请求本身被拒
# （模型名错误、缺鉴权头、会话 ID 非法），原样重发永远得到同样的结果。
_HTTP_ERROR_CODES = {
    "rate_limited": "LLM_RATE_LIMITED",
    "upstream": "LLM_UPSTREAM_ERROR",
    "rejected": "LLM_REQUEST_REJECTED",
}

# 确定性 4xx 的用户可见文案：与"暂时不可用，请稍后重试"区分开——重试不会好，
# 需要运维介入检查模型与网关配置，把原因说清楚比让用户反复重试更有用。
_REJECTED_MESSAGE = "AI 服务调用被拒绝，请联系管理员检查模型配置"


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


def _resolve_client(settings: Settings) -> httpx.AsyncClient:
    """取出（必要时创建）进程级共享客户端。

    双重检查加锁：并发调用会同时进入这里，无锁会建出两个客户端并泄漏其中一个
    （与 llm_call_log 的引擎单例同一处理方式）。
    """
    base_url = settings.deepseek_base_url.rstrip("/")
    timeout = float(settings.deepseek_timeout_seconds)
    key = (base_url, timeout)
    client = _SHARED_CLIENTS.get(key)
    if client is None:
        with _CLIENT_LOCK:
            client = _SHARED_CLIENTS.get(key)
            if client is None:
                client = httpx.AsyncClient(
                    base_url=base_url,
                    timeout=timeout,
                    limits=httpx.Limits(
                        max_connections=_CLIENT_MAX_CONNECTIONS,
                        max_keepalive_connections=_CLIENT_MAX_KEEPALIVE,
                    ),
                    headers={"User-Agent": _USER_AGENT},
                )
                _SHARED_CLIENTS[key] = client
    return client


def reset_shared_clients() -> None:
    """清空共享客户端缓存（不关闭连接）。

    供测试隔离与进程退出使用：测试替身被缓存在这里会串味，
    真实进程退出则由 close_shared_clients 负责优雅关闭。
    """
    with _CLIENT_LOCK:
        _SHARED_CLIENTS.clear()


async def close_shared_clients() -> None:
    """关闭全部共享客户端，供 FastAPI lifespan / worker 退出时调用。

    关闭失败只记 warning：进程已在退出路径上，这里抛异常只会掩盖真实的退出原因。
    """
    with _CLIENT_LOCK:
        clients = list(_SHARED_CLIENTS.values())
        _SHARED_CLIENTS.clear()
    for client in clients:
        try:
            await client.aclose()
        except Exception:  # noqa: BLE001 - 退出路径上的关闭失败不应中断退出
            log.warning("关闭共享 HTTP 客户端失败，已忽略", exc_info=True)


def _classify_http_status(status_code: int) -> str:
    """把 HTTP 状态码归入三类失败：限流 / 上游故障 / 请求被拒。

    判据必须与调用点的"非 2xx 即失败"保持同一套阈值，否则会出现
    "分类函数说这是确定性失败、实际执行路径却按可重试处理"的自相矛盾。
    """
    if status_code == 429:
        return _HTTP_ERROR_CODES["rate_limited"]
    if status_code >= 500:
        return _HTTP_ERROR_CODES["upstream"]
    if 300 <= status_code < 400:
        # 3xx：httpx 默认不跟随重定向，网关地址变更、证书跳转或路径改写都会落到这里。
        # 这是环境配置问题而不是请求本身有问题，因此归入可重试，
        # 不能复用"请联系管理员检查模型配置"——那会把瞬时跳转说成配置错误。
        return _HTTP_ERROR_CODES["upstream"]
    if status_code >= 400:
        return _HTTP_ERROR_CODES["rejected"]
    # 其余（1xx 等本不该出现在最终响应里的状态码）：按上游异常处理，
    # 既不谎称请求有问题，也不白白放弃一次重试机会。
    return _HTTP_ERROR_CODES["upstream"]


def _body_snippet(response: httpx.Response, limit: int) -> str:
    """截取响应体摘要，供日志记录失败原因。

    网关（尤其聚合网关）把真正的失败原因放在响应体里，例如 400 + 
    {"error":{"message":"MissingSessionID"}}。此前 raise_for_status 把这部分
    直接丢掉，导致 5 次连续 400 在日志与看板上都只能看到"服务暂时不可用"，
    排查只能去翻网关文档。这里主动记录下来，并压掉换行避免污染单行日志。
    读取失败（二进制体、编码异常）时降级为提示串，绝不因为记录日志而改变异常路径。
    """
    if limit <= 0:
        return "（已按配置关闭响应体记录）"
    try:
        text = response.text
    except Exception:  # noqa: BLE001 - 记录失败原因不得反过来制造异常
        return "（响应体不可读）"
    compact = " ".join(text.split())
    if len(compact) > limit:
        return f"{compact[:limit]}…（已截断，共 {len(compact)} 字符）"
    return compact


def _build_http_error(response: httpx.Response, settings: Settings, what: str) -> AppError:
    """把非 2xx 响应转成带分类错误码的 AppError，并记录状态码与响应体摘要。"""
    status_code = response.status_code
    code = _classify_http_status(status_code)
    snippet = _body_snippet(response, settings.llm_error_body_log_chars)
    log.error(
        "DeepSeek 生成%s失败 http_status=%s error_code=%s 响应体=%s",
        what,
        status_code,
        code,
        snippet,
    )
    message = (
        _REJECTED_MESSAGE
        if code == _HTTP_ERROR_CODES["rejected"]
        else "AI 生成服务暂时不可用，请稍后重试"
    )
    return AppError(message, code=code, status_code=502)


def _offline_session_id() -> str:
    """无链路上下文时的网关会话标识：进程内稳定、跨进程隔离。"""
    return _OFFLINE_SESSION_ID


async def call_deepseek_json(
    messages: list[dict[str, str]],
    settings: Settings,
    *,
    scene: str,
    what: str = "结果",
    repair_error: str | None = None,
    unavailable_message: str = "AI 生成服务暂时不可用，请稍后重试",
    max_tokens: int | None = None,
    prompt_key: str | None = None,
) -> dict[str, Any]:
    """调用 DeepSeek JSON 模式；API Key 缺失时给出明确配置错误。

    scene 是机器可读的调用场景标识（question_generate / evaluate / report_generate /
    profile_analyze / resume_parse），与面向用户的中文 what 分离：what 只用于错误文案，
    scene 用于埋点归因与看板筛选。刻意设为必填——新增调用点时忘记登记场景会直接报错，
    而不是静默产生一条无法归类的观测数据。

    repair_error 非空时在消息末尾追加修复指令，供调用方在校验失败后
    做一次带上下文的修复调用（如 LLM_INVALID_JSON / 字段校验失败）。

    unavailable_message 供各场景保留自己原有的不可用文案（12.1 收敛前的
    "画像分析服务暂时不可用"、"简历结构化服务暂时不可用"）。该文案会写进
    ai_task.error_message 并回显到前端，属用户可见文案，收敛入口时不得改写。
    注意它只覆盖"瞬时失败"（429/5xx/传输异常）；确定性 4xx 用统一文案，
    因为让用户反复重试一个配置错误没有意义。

    max_tokens 用于单条提示词覆盖全局输出上限：输出长度随输入规模变化的调用
    （如参考答案生成）要按自己的规模给上限，而**截断后的 JSON 必然解析失败**，
    表现出来只是"模型返回的 JSON 格式无效"。未指定时沿用全局配置；
    实际生效值会写进调用日志的 max_tokens 列，便于事后判断失败是否为截断所致。

    prompt_key 是稳定的人工可读标识（如 question_generate.follow_up），
    与 prompt_version 一起回答"指标变化是 prompt 改的还是模型波动"。

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
        "max_tokens": max_tokens or settings.deepseek_max_tokens,
        "response_format": {"type": "json_object"},
    }
    log.info("调用 DeepSeek 生成%s scene=%s repair=%s", what, scene, bool(repair_error))
    started = time.perf_counter()
    # usage 由响应体的 usage 字段填充。它在内容解析之前就已取到，因此"模型返回的内容
    # 不合法"这类失败（AppError）同样能记下真实 token 消耗——这些调用在上游已经计费，
    # 漏记会让成本统计恰好丢掉最贵的一批失败。只有传输层异常（响应都没拿到）才为空。
    usage: dict[str, Any] = {}
    # 本次调用的 HTTP 状态码与停止原因：两者一起才能把"失败"拆成可行动的类别，
    # 例如 http_status=200 + finish_reason=length 是"输出被截断"，而 400 是"请求被拒"。
    http_status: int | None = None
    finish_reason: str | None = None
    try:
        client = _resolve_client(settings)
        response = await client.post(
            "/chat/completions",
            headers={
                "Authorization": f"Bearer {api_key}",
                # x-opencode-session：OpenCode Zen/Go 要求按会话维度提供稳定的 session id
                # （用于请求路由与提示词缓存），缺失时直接返回 400 MissingSessionID，
                # 即"换了 base_url 就整个服务不可用"。链路追踪 ID 是最贴合的现成会话标识：
                # 一次用户请求 / 一条 MQ 任务内的多次 LLM 调用共享同一 trace_id，
                # 天然满足"同会话稳定、跨会话隔离"。其它网关会忽略这个未知头。
                # 前提是 trace_id 本身不再粘连（见 plan 的 trace 作用域修复），
                # 否则所有后台任务会挤进同一个网关会话。
                "x-opencode-session": current_trace_id() or _offline_session_id(),
            },
            json=payload,
        )
        http_status = response.status_code
        # 非 2xx 一律按失败处理：与 _classify_http_status 使用同一套判据。
        # 此前只判 >=400，3xx（httpx 默认不跟随重定向）会漏到下面的正文解析，
        # 最终以"响应体不是 JSON"的形态落到 LLM_REQUEST_FAILED，分类函数成了死代码。
        if not 200 <= http_status < 300:
            # 不再用 raise_for_status：它会把响应体（真正的失败原因）一起丢掉。
            raise _build_http_error(response, settings, what)
        body = response.json()
        # 网关错误页可能返回 JSON 数组/字符串：不能假设响应体是对象，
        # 否则抛出的 AttributeError 不在下方 except 元组内，会变成未处理异常。
        if not isinstance(body, dict):
            raise TypeError(f"DeepSeek 响应体不是 JSON 对象：{type(body).__name__}")
        # usage 是 OpenAI 兼容协议的可选字段；缺失或类型异常（非对象）时保持空，
        # 不因此判定调用失败，也避免后续 usage.get 抛 AttributeError。
        raw_usage = body.get("usage")
        usage = raw_usage if isinstance(raw_usage, dict) else {}
        choices = body.get("choices")
        choice = choices[0] if isinstance(choices, list) and choices else None
        if isinstance(choice, dict):
            raw_finish = choice.get("finish_reason")
            finish_reason = raw_finish if isinstance(raw_finish, str) else None
        content = body["choices"][0]["message"]["content"]
        try:
            parsed = parse_json_content(content, what=what)
        except AppError as parse_error:
            # 截断是"JSON 格式无效"最常见的真实原因，但两者此前共用一个错误码，
            # 看板上无法区分"要抬上限"还是"模型乱输出"。这里给出可判定的专用错误码。
            if parse_error.code == "LLM_INVALID_JSON" and finish_reason == "length":
                raise AppError(
                    f"模型输出的{what}达到输出上限被截断，未能形成完整 JSON",
                    code="LLM_OUTPUT_TRUNCATED",
                    status_code=502,
                ) from parse_error
            raise
        if finish_reason == "length":
            # 竟然解析成功但已贴到上限：本次结果可用，但下一条同类请求极可能被截断。
            log.warning(
                "DeepSeek 生成%s的 finish_reason=length，输出已贴上限 max_tokens=%s，"
                "建议缩小单次请求规模或抬高上限",
                what,
                payload["max_tokens"],
            )
    except AppError as exc:
        # 业务侧可识别的失败（JSON 为空/格式无效/被截断/HTTP 非 2xx）原样落库，
        # 错误码便于按原因聚合。usage 必须一起带上：此处失败的是"内容校验"或
        # "响应被拒"，HTTP 调用本身可能已成功，上游已按 token 计费。缺失它会让失败
        # 记录看不出是"截断"还是"格式跑偏"。
        await _record_call(
            messages,
            settings,
            scene=scene,
            what=what,
            started=started,
            repair_error=repair_error,
            usage=usage,
            http_status=http_status,
            finish_reason=finish_reason,
            error_code=exc.code,
            error_message=exc.message,
            max_tokens=max_tokens,
            prompt_key=prompt_key,
        )
        raise
    except (
        httpx.HTTPError,
        KeyError,
        IndexError,
        TypeError,
        json.JSONDecodeError,
        UnicodeDecodeError,
    ) as exc:
        log.exception("DeepSeek 生成%s失败", what)
        error_message = unavailable_message
        await _record_call(
            messages,
            settings,
            scene=scene,
            what=what,
            started=started,
            repair_error=repair_error,
            http_status=http_status,
            finish_reason=finish_reason,
            error_code="LLM_REQUEST_FAILED",
            error_message=error_message,
            max_tokens=max_tokens,
            prompt_key=prompt_key,
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
        what=what,
        started=started,
        repair_error=repair_error,
        usage=usage,
        http_status=http_status,
        finish_reason=finish_reason,
        max_tokens=max_tokens,
        prompt_key=prompt_key,
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
    what: str = "结果",
    usage: dict[str, Any] | None = None,
    error_code: str | None = None,
    error_message: str | None = None,
    max_tokens: int | None = None,
    prompt_key: str | None = None,
    http_status: int | None = None,
    finish_reason: str | None = None,
) -> None:
    """组装并写入一条调用记录，字段全部取自本次调用的实际观测值。

    注意这里调用的是 messages（未追加修复指令的原始提示词）：request_hash 表示
    "同一个业务请求"，修复调用与首次调用应当同哈希，靠 retry_attempt 区分，
    这样"修复率"才是一个可统计的量。

    业务维度（biz_type/biz_id/attempt_no）来自入口处设置的上下文变量，
    调用点不必逐层透传；上下文缺失时保持为空，与"未填充"的旧行为一致。

    整个函数体包在 try 内：埋点属于旁路能力，任何取值/组装异常都不得让一次
    已经成功的 LLM 调用变成 500，也不得让失败路径的异常类型发生变化。
    """
    try:
        usage = usage if isinstance(usage, dict) else {}
        context = current_llm_context()
        record = LlmCallRecord(
            scene=scene,
            provider=settings.deepseek_provider,
            model=settings.deepseek_model,
            status="FAILED" if error_code else "SUCCESS",
            latency_ms=int((time.perf_counter() - started) * 1000),
            retry_attempt=1 if repair_error else 0,
            trace_id=current_trace_id(),
            biz_type=context.biz_type,
            biz_id=context.biz_id,
            prompt_key=prompt_key,
            prompt_version=settings.llm_prompt_version,
            request_hash=hash_messages(messages),
            request_chars=sum(len(message.get("content") or "") for message in messages),
            temperature=settings.deepseek_temperature,
            max_tokens=max_tokens or settings.deepseek_max_tokens,
            token_input=usage.get("prompt_tokens"),
            token_output=usage.get("completion_tokens"),
            token_total=usage.get("total_tokens"),
            http_status=http_status,
            finish_reason=finish_reason,
            attempt_no=context.attempt_no,
            error_code=error_code,
            error_message=error_message,
        )
        await record_llm_call(record, settings=settings)
    except Exception:
        log.warning("LLM 调用日志组装失败，已忽略（不影响本次调用）", exc_info=True)

