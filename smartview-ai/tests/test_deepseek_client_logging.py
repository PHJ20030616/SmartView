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
        # 记录最后一次请求体，用于断言输出上限等参数确实发给了上游
        self.last_json: dict | None = None

    def __call__(self, *args, **kwargs):  # noqa: ANN002, ANN003 - 对齐 AsyncClient 构造签名
        return self

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc_info) -> None:
        return None

    async def post(self, *args, **kwargs) -> httpx.Response:  # noqa: ANN002, ANN003
        self.calls += 1
        self.last_json = kwargs.get("json")
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


def _install_client(monkeypatch, response: httpx.Response | Exception) -> _CapturingClient:
    client = _CapturingClient(response)
    monkeypatch.setattr(deepseek_client.httpx, "AsyncClient", client)
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
