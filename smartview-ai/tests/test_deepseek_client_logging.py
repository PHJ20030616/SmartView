"""埋点三路径的自动化测试：成功 / AppError / 传输异常。

12.3 的核心断言是"一次调用恰好写一条记录，且字段语义正确"。这条断言此前只能靠
端到端联调人工确认，任何重构（例如把埋点挪进 try 块、或改成只在成功时埋点）
都不会让别的测试变红。这里用 httpx 替身把三条路径固定下来。

同时覆盖两条"埋点不得改变主流程"的硬约束：
- 响应体不是 JSON 对象、usage 类型异常时，成功路径不能被取值或埋点拖成异常；
- 埋点自身抛异常时，调用结果必须照常返回。

写法与仓库既有异步测试保持一致：同步测试内用 asyncio.run 驱动（未装 pytest-asyncio）。
"""
import asyncio
import json

import httpx
import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.core.trace import reset_trace_id, set_trace_id
from app.observability.llm_call_log import LlmCallRecord
from app.services import deepseek_client

MESSAGES = [{"role": "user", "content": "为候选人生成一道题"}]


def _settings(**overrides) -> Settings:
    """构造可直接用于调用的最小配置。"""
    base = {
        "deepseek_api_key": "sk-test",
        "deepseek_base_url": "https://api.deepseek.test",
        "deepseek_model": "deepseek-v4-flash",
        "llm_prompt_version": "p-test",
    }
    base.update(overrides)
    return Settings(**base)


class _CapturingClient:
    """httpx.AsyncClient 替身：记录调用次数并返回预设响应或抛出预设异常。"""

    def __init__(self, response: httpx.Response | Exception) -> None:
        self.response = response
        self.calls = 0
        # 记录最后一次请求体与请求头，用于断言输出上限、客户端身份等参数确实发给了上游
        self.last_json: dict | None = None
        self.last_headers: dict | None = None
        # 构造参数：共享客户端把 User-Agent 等不变头放在客户端级默认头里，只在构造时传一次
        self.init_kwargs: dict | None = None

    def __call__(self, *args, **kwargs):  # noqa: ANN002, ANN003 - 对齐 AsyncClient 构造签名
        self.init_kwargs = kwargs
        return self

    @property
    def init_headers(self) -> dict:
        """构造时传入的默认头；未传入时返回空 dict，便于断言直接取值。"""
        return (self.init_kwargs or {}).get("headers") or {}

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info) -> None:
        return None

    async def post(self, *args, **kwargs) -> httpx.Response:  # noqa: ANN002, ANN003
        self.calls += 1
        self.last_json = kwargs.get("json")
        self.last_headers = kwargs.get("headers")
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


def _install_client(monkeypatch, response: httpx.Response | Exception) -> _CapturingClient:
    client = _CapturingClient(response)
    monkeypatch.setattr(deepseek_client.httpx, "AsyncClient", client)
    # 共享客户端是按 (base_url, timeout) 缓存的进程级单例：不清空的话，
    # 上一个测试安装的替身会被复用，断言就会打到别的响应上。
    # 真正的"进程内复用"行为由 test_shared_client_is_reused_across_calls 覆盖。
    deepseek_client.reset_shared_clients()
    return client


def _capture_records(monkeypatch) -> list[LlmCallRecord]:
    """替换掉真实写库动作，只收集要落库的记录。"""
    records: list[LlmCallRecord] = []

    async def fake_record(record: LlmCallRecord, **kwargs) -> None:  # noqa: ANN003
        records.append(record)

    monkeypatch.setattr(deepseek_client, "record_llm_call", fake_record)
    return records


def _json_response(payload: dict, status_code: int = 200) -> httpx.Response:
    return httpx.Response(
        status_code=status_code,
        request=httpx.Request("POST", "https://api.deepseek.test/chat/completions"),
        json=payload,
    )


def _raw_json_response(payload: object, status_code: int = 200) -> httpx.Response:
    return httpx.Response(
        status_code=status_code,
        request=httpx.Request("POST", "https://api.deepseek.test/chat/completions"),
        content=json.dumps(payload).encode("utf-8"),
        headers={"content-type": "application/json"},
    )


def _completion(content: str, usage: object | None = None) -> dict:
    body = {"choices": [{"message": {"content": content}}]}
    if usage is not None:
        body["usage"] = usage
    return body


def test_success_path_records_exactly_one_row(monkeypatch) -> None:
    records = _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        _json_response(
            _completion(
                '{"ok": true}',
                {"prompt_tokens": 11, "completion_tokens": 22, "total_tokens": 33},
            )
        ),
    )

    result = asyncio.run(
        deepseek_client.call_deepseek_json(
            MESSAGES, _settings(), scene="question_generate", what="出题"
        )
    )

    assert result == {"ok": True}
    assert len(records) == 1
    record = records[0]
    assert record.scene == "question_generate"
    assert record.status == "SUCCESS"
    assert (record.token_input, record.token_output, record.token_total) == (11, 22, 33)
    assert record.retry_attempt == 0
    assert record.prompt_version == "p-test"
    assert record.error_code is None
    # 记录的是未追加修复指令的原始提示词
    assert record.request_chars == len(MESSAGES[0]["content"])


def test_app_error_path_records_failed_row_with_business_code(monkeypatch) -> None:
    records = _capture_records(monkeypatch)
    # 模型返回的不是 JSON：parse_json_content 抛 AppError，错误码应原样落库
    _install_client(monkeypatch, _json_response(_completion("这不是 JSON")))

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="evaluate", what="回答评估"
            )
        )

    assert excinfo.value.code == "LLM_INVALID_JSON"
    assert len(records) == 1
    assert records[0].status == "FAILED"
    assert records[0].error_code == "LLM_INVALID_JSON"
    # 该响应体本身没有 usage 字段，因此 token 保持为空（与"有 usage 必须记录"区分开）
    assert records[0].token_total is None


def test_invalid_json_failure_still_records_token_usage(monkeypatch) -> None:
    """内容不合法但 HTTP 成功时，仍要记下 token 消耗。

    这类失败在上游已按 token 计费，漏记会让成本统计丢掉最贵的一批失败；
    也无法通过 token_output 是否贴近 max_tokens 判断输出是否被截断。
    """
    records = _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        _json_response(
            _completion(
                '{"items": [{"question": "被截断的',
                {"prompt_tokens": 900, "completion_tokens": 8192, "total_tokens": 9092},
            )
        ),
    )

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="report_generate", what="参考答案"
            )
        )

    assert excinfo.value.code == "LLM_INVALID_JSON"
    assert len(records) == 1
    record = records[0]
    assert record.status == "FAILED"
    assert record.error_code == "LLM_INVALID_JSON"
    # 关键断言：失败记录也要带真实的输入/输出/总 token
    assert (record.token_input, record.token_output, record.token_total) == (900, 8192, 9092)


def test_transport_error_path_records_failed_row(monkeypatch) -> None:
    records = _capture_records(monkeypatch)
    _install_client(monkeypatch, httpx.ConnectError("connection refused"))

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="report_generate", what="报告"
            )
        )

    assert excinfo.value.code == "LLM_REQUEST_FAILED"
    assert len(records) == 1
    assert records[0].status == "FAILED"
    assert records[0].error_code == "LLM_REQUEST_FAILED"


def test_scene_specific_unavailable_message_is_preserved(monkeypatch) -> None:
    """收敛入口不得改写各场景原有的用户可见文案（plan_1.1 §5.2）。"""
    records = _capture_records(monkeypatch)
    _install_client(monkeypatch, httpx.ReadTimeout("timeout"))

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES,
                _settings(),
                scene="profile_analyze",
                what="画像分析",
                unavailable_message="画像分析服务暂时不可用，请稍后重试",
            )
        )

    assert excinfo.value.message == "画像分析服务暂时不可用，请稍后重试"
    assert records[0].error_message == "画像分析服务暂时不可用，请稍后重试"


def test_repair_call_keeps_same_hash_and_marks_retry(monkeypatch) -> None:
    """修复调用与首次调用同 request_hash，靠 retry_attempt 区分，"修复率"才可统计。"""
    records = _capture_records(monkeypatch)
    _install_client(monkeypatch, _json_response(_completion('{"ok": true}')))

    async def _run_both() -> None:
        await deepseek_client.call_deepseek_json(
            MESSAGES, _settings(), scene="report_generate", what="报告"
        )
        await deepseek_client.call_deepseek_json(
            MESSAGES,
            _settings(),
            scene="report_generate",
            what="报告",
            repair_error="缺少字段",
        )

    asyncio.run(_run_both())

    assert [record.retry_attempt for record in records] == [0, 1]
    assert records[0].request_hash == records[1].request_hash


def _completion_with_finish(content: str, finish_reason: str, usage: dict | None = None) -> dict:
    """构造带 finish_reason 的响应体：截断诊断完全依赖这个字段。"""
    choice = {"message": {"content": content}, "finish_reason": finish_reason}
    body: dict = {"choices": [choice]}
    if usage is not None:
        body["usage"] = usage
    return body


def test_truncated_output_gets_dedicated_error_code(monkeypatch) -> None:
    """finish_reason=length + JSON 解析失败 → LLM_OUTPUT_TRUNCATED。

    历史上这类失败与"模型乱输出"共用 LLM_INVALID_JSON，看板无法区分
    "该抬输出上限"还是"该改提示词"，只能靠 token_output 是否贴近 max_tokens 去猜。
    """
    records = _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        _json_response(
            _completion_with_finish(
                '{"referenceAnswers": [{"questionId": "1", "referenceContent": "被截断',
                "length",
                {"prompt_tokens": 14219, "completion_tokens": 8192, "total_tokens": 22411},
            )
        ),
    )

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="report_generate", what="参考答案"
            )
        )

    assert excinfo.value.code == "LLM_OUTPUT_TRUNCATED"
    assert len(records) == 1
    # 截断诊断的两个关键证据必须落库：停止原因与实际输出量
    assert records[0].finish_reason == "length"
    assert records[0].token_output == 8192
    assert records[0].status == "FAILED"
    assert records[0].error_code == "LLM_OUTPUT_TRUNCATED"


def test_normal_finish_reason_keeps_invalid_json_code(monkeypatch) -> None:
    """finish_reason=stop 的非法 JSON 仍然报 LLM_INVALID_JSON，不能被截断逻辑吞掉。"""
    records = _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        _json_response(_completion_with_finish("这不是 JSON", "stop")),
    )

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="evaluate", what="回答评估"
            )
        )

    assert excinfo.value.code == "LLM_INVALID_JSON"
    assert records[0].finish_reason == "stop"


@pytest.mark.parametrize(
    ("status_code", "expected_code", "expected_retryable"),
    [
        # 429/5xx 是上游瞬时状态，退避重试有意义
        (429, "LLM_RATE_LIMITED", True),
        (500, "LLM_UPSTREAM_ERROR", True),
        (503, "LLM_UPSTREAM_ERROR", True),
        # 其余 4xx 是请求本身被拒，原样重发永远得到同样的结果
        (400, "LLM_REQUEST_REJECTED", False),
        (404, "LLM_REQUEST_REJECTED", False),
        # 3xx：httpx 默认不跟随重定向，网关换地址/证书跳转都会走到这里。
        # 属环境问题而非请求问题，必须归入可重试，且不能宣称"请检查模型配置"。
        (302, "LLM_UPSTREAM_ERROR", True),
    ],
)
def test_http_failures_are_classified_by_retryability(
    monkeypatch, status_code: int, expected_code: str, expected_retryable: bool
) -> None:
    """HTTP 非 2xx 必须按"瞬时/确定性"分成可行动的错误码，并记下状态码与响应体。

    生产故障：切到 opencode 网关后 resume_parse 连续 5 次 400（缺 x-opencode-session），
    但埋点只留下"LLM_REQUEST_FAILED + 服务暂时不可用"，状态码与网关给的失败原因
    全被 raise_for_status 丢掉，排查完全无从下手。
    """
    from app.workers import report_worker

    records = _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        httpx.Response(
            status_code=status_code,
            request=httpx.Request("POST", "https://api.deepseek.test/chat/completions"),
            json={"error": {"message": "MissingSessionID"}},
        ),
    )

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES,
                _settings(),
                scene="resume_parse",
                what="简历",
                unavailable_message="简历结构化服务暂时不可用，请稍后重试",
            )
        )

    assert excinfo.value.code == expected_code
    assert records[0].http_status == status_code
    assert records[0].error_code == expected_code
    # 分类结论要与 worker 的重试白名单一致，否则"分类"只是好看而已
    assert (expected_code in report_worker._RETRYABLE_APP_ERROR_CODES) is expected_retryable


def test_redirect_response_does_not_fall_through_to_body_parsing(monkeypatch) -> None:
    """3xx 必须走"上游异常"分支，而不是漏到正文解析。

    此前判据是 http_status >= 400，3xx 会继续去解析重定向页（HTML），
    最终以"响应体不是 JSON"的形态报 LLM_REQUEST_FAILED——既丢掉了状态码，
    也把一次网关跳转描述成请求格式问题，看板上无法与真正的坏响应区分。
    """
    records = _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        httpx.Response(
            status_code=302,
            headers={"location": "https://gateway.test/chat/completions"},
            text="<html>moved</html>",
            request=httpx.Request("POST", "https://api.deepseek.test/chat/completions"),
        ),
    )

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="resume_parse", what="简历"
            )
        )

    assert excinfo.value.code == "LLM_UPSTREAM_ERROR"
    # 状态码必须落库，否则运维看不出"上游在重定向"
    assert records[0].http_status == 302
    assert records[0].error_code == "LLM_UPSTREAM_ERROR"
    # 环境问题可重试：用户看到的应该是"稍后重试"，而不是"请联系管理员检查配置"
    assert "稍后重试" in excinfo.value.message


def test_rejected_request_does_not_promise_user_to_retry(monkeypatch) -> None:
    """确定性 4xx 不能复用"请稍后重试"文案：重试不会好，只会掩盖配置问题。"""
    _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        httpx.Response(
            status_code=400,
            request=httpx.Request("POST", "https://api.deepseek.test/chat/completions"),
            json={"error": {"message": "missing x-opencode-session"}},
        ),
    )

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="resume_parse", what="简历"
            )
        )

    assert "稍后重试" not in excinfo.value.message
    assert "模型配置" in excinfo.value.message


def test_http_failure_logs_status_and_response_body(monkeypatch, caplog) -> None:
    """非 2xx 的响应体必须进日志——网关把真正的失败原因写在那里。"""
    _capture_records(monkeypatch)
    _install_client(
        monkeypatch,
        httpx.Response(
            status_code=400,
            request=httpx.Request("POST", "https://api.deepseek.test/chat/completions"),
            json={"error": {"message": "MissingSessionID"}},
        ),
    )

    with caplog.at_level("ERROR"):
        with pytest.raises(AppError):
            asyncio.run(
                deepseek_client.call_deepseek_json(
                    MESSAGES, _settings(), scene="resume_parse", what="简历"
                )
            )

    logged = "\n".join(record.getMessage() for record in caplog.records)
    assert "http_status=400" in logged
    assert "MissingSessionID" in logged


def test_shared_client_is_reused_across_calls(monkeypatch) -> None:
    """进程内复用同一 AsyncClient：每调用新建客户端等于每次重做 DNS+TCP+TLS 握手。"""
    records = _capture_records(monkeypatch)
    client = _install_client(monkeypatch, _json_response(_completion('{"ok": true}')))
    deepseek_client.reset_shared_clients()

    async def _run_twice() -> None:
        await deepseek_client.call_deepseek_json(
            MESSAGES, _settings(), scene="evaluate", what="回答评估"
        )
        await deepseek_client.call_deepseek_json(
            MESSAGES, _settings(), scene="evaluate", what="回答评估"
        )

    asyncio.run(_run_twice())

    # 两次调用只应构造一次客户端
    assert client.calls == 2
    assert len(deepseek_client._SHARED_CLIENTS) == 1
    assert len(records) == 2


def test_business_context_reaches_the_record(monkeypatch) -> None:
    """入口设置的业务上下文要落到埋点，biz_type/biz_id 才能在按会话归因时可用。"""
    from app.core.llm_context import reset_llm_context, set_llm_context

    records = _capture_records(monkeypatch)
    _install_client(monkeypatch, _json_response(_completion('{"ok": true}')))
    token = set_llm_context(biz_type="interview_session", biz_id=14, attempt_no=2)
    try:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES,
                _settings(),
                scene="report_generate",
                what="报告评语",
                prompt_key="report_generate.narrative",
            )
        )
    finally:
        reset_llm_context(token)

    assert records[0].biz_type == "interview_session"
    assert records[0].biz_id == 14
    assert records[0].attempt_no == 2
    assert records[0].prompt_key == "report_generate.narrative"
    assert records[0].http_status == 200
    assert records[0].finish_reason is None


def test_non_object_response_body_is_reported_not_raised(monkeypatch) -> None:
    """网关返回 JSON 数组时必须走"调用失败"分支，而不是未处理异常。"""
    records = _capture_records(monkeypatch)
    _install_client(monkeypatch, _raw_json_response(["unexpected"]))

    with pytest.raises(AppError) as excinfo:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="question_generate", what="出题"
            )
        )

    assert excinfo.value.code == "LLM_REQUEST_FAILED"
    assert len(records) == 1
    assert records[0].status == "FAILED"


def test_non_object_usage_does_not_break_success_path(monkeypatch) -> None:
    """usage 类型异常（数组/字符串）时，成功的调用必须照常返回。"""
    records = _capture_records(monkeypatch)
    _install_client(monkeypatch, _json_response(_completion('{"ok": true}', ["bad"])))

    result = asyncio.run(
        deepseek_client.call_deepseek_json(
            MESSAGES, _settings(), scene="question_generate", what="出题"
        )
    )

    assert result == {"ok": True}
    assert len(records) == 1
    assert records[0].status == "SUCCESS"
    assert records[0].token_total is None


def test_max_tokens_override_reaches_upstream_and_is_recorded(monkeypatch) -> None:
    """单次调用的输出上限覆盖必须同时作用到请求与日志。

    日志里记的是**实际生效值**：事后看到 max_tokens=8192 而 token_output=8192，
    就能判断这次失败是输出被截断，而不是模型自由发挥。
    """
    records = _capture_records(monkeypatch)
    client = _install_client(monkeypatch, _json_response(_completion('{"ok": true}')))

    asyncio.run(
        deepseek_client.call_deepseek_json(
            MESSAGES,
            _settings(),
            scene="report_generate",
            what="参考答案",
            max_tokens=16384,
        )
    )

    assert client.last_json is not None
    assert client.last_json["max_tokens"] == 16384
    assert records[0].max_tokens == 16384


def test_session_header_carries_trace_id_as_conversation_id(monkeypatch) -> None:
    """x-opencode-session 必须带稳定会话标识，否则网关直接 400 MissingSessionID。

    用链路追踪 ID 作会话标识：一次请求/一条 MQ 任务内的多次 LLM 调用共享同一 trace_id，
    换提供商（opencode.ai）时正是靠这个头才能把请求路由出去。
    """
    records = _capture_records(monkeypatch)
    client = _install_client(monkeypatch, _json_response(_completion('{"ok": true}')))
    token = set_trace_id("11111111-2222-3333-4444-555555555555")
    try:
        asyncio.run(
            deepseek_client.call_deepseek_json(
                MESSAGES, _settings(), scene="evaluate", what="回答评估"
            )
        )
    finally:
        reset_trace_id(token)

    assert client.last_headers is not None
    assert client.last_headers["x-opencode-session"] == "11111111-2222-3333-4444-555555555555"
    # 客户端自报身份，不能是 httpx 的默认 UA。UA 属于客户端级默认头
    # （放在构造参数里，避免每次请求重复拼装），因此断言在构造参数上。
    assert client.init_headers["User-Agent"] == "smartview-ai/0.1.0"
    assert records[0].trace_id == "11111111-2222-3333-4444-555555555555"


def test_session_header_falls_back_without_trace_context(monkeypatch) -> None:
    """无链路上下文（脚本/定时任务）时用兜底会话标识，不能发空头。

    兜底值必须是"进程内稳定、跨进程隔离"的：早期实现用固定字符串
    smartview-ai-offline，等于把所有离线任务的 LLM 调用塞进同一个网关会话。
    """
    _capture_records(monkeypatch)
    client = _install_client(monkeypatch, _json_response(_completion('{"ok": true}')))

    asyncio.run(
        deepseek_client.call_deepseek_json(
            MESSAGES, _settings(), scene="evaluate", what="回答评估"
        )
    )

    assert client.last_headers is not None
    session_id = client.last_headers["x-opencode-session"]
    assert session_id.startswith("smartview-ai-offline-")
    # 同一进程内稳定：否则每次调用都会开一个新的网关会话，失去提示词缓存收益
    assert session_id == deepseek_client._offline_session_id()


def test_record_failure_never_breaks_the_call(monkeypatch) -> None:
    """埋点自身抛异常（库不可用、字段组装 bug）时，主流程必须无感。"""

    async def exploding_record(record: LlmCallRecord, **kwargs) -> None:  # noqa: ANN003
        raise RuntimeError("数据库不可用")

    monkeypatch.setattr(deepseek_client, "record_llm_call", exploding_record)
    _install_client(monkeypatch, _json_response(_completion('{"ok": true}')))

    result = asyncio.run(
        deepseek_client.call_deepseek_json(
            MESSAGES, _settings(), scene="question_generate", what="出题"
        )
    )

    assert result == {"ok": True}
