"""LLM 调用归因上下文。

埋点需要三个"调用点未必显式传递、但每个入口都知道"的维度：

- biz_type / biz_id：这次调用服务哪个业务对象（会话 / 简历文件 / 任务）。
  llm_call_log 建表时就留了这两列，但一直没被填充，导致无法回答"哪个会话最贵"。
- attempt_no：本次业务任务的第几次尝试。MQ worker 的 retryCount 会让同一个
  request_hash 在表里出现多条记录，缺这个字段时看板只能看到"重复调用"，
  看不出它们其实是同一次失败的重试（retry_attempt 只表达"是否修复调用"）。

实现沿用 app.core.trace 已验证的模式：contextvar + set/reset，入口处设置、
finally 里恢复。之所以不用函数参数逐层传递，是因为调用链要穿过
graph 节点 → service → client 三层，为埋点维度改动全部函数签名会污染业务接口，
而 contextvar 与"一次请求/一条 MQ 任务"的作用域天然对齐。
"""

from __future__ import annotations

import contextvars
from dataclasses import dataclass
from typing import Any

# 保存当前业务上下文的上下文变量；未设置时为空上下文（字段全部为 None）。
LLM_CONTEXT: contextvars.ContextVar["LlmContext"] = contextvars.ContextVar(
    "llm_context", default=None
)


@dataclass(frozen=True, slots=True)
class LlmContext:
    """一次请求 / 一条 MQ 任务携带的埋点归因维度。

    刻意不含 prompt_key：它是"每次调用"的属性而不是"每个入口"的属性
    （同一次回答评估既生成候选题又评估回答，两者的 prompt_key 不同），
    放在入口级上下文里反而容易被误设成同一个值，因此由调用点显式传入。
    """

    biz_type: str | None = None
    biz_id: int | None = None
    attempt_no: int = 0


def set_llm_context(
    *,
    biz_type: str | None = None,
    biz_id: Any = None,
    attempt_no: Any = 0,
) -> contextvars.Token:
    """写入埋点归因维度，返回用于恢复上下文的 token。

    biz_id 与 attempt_no 做一次宽松的整型归一：调用方常常直接传
    pydantic/请求模型里的字符串 ID（如 sessionId="14"），
    如果在这里抛异常会波及业务主流程——埋点绝不能因为一个脏 ID 让面试失败。
    无法转成整型时按 None / 0 处理，宁可少一个维度，不可多一次故障。
    """
    return LLM_CONTEXT.set(
        LlmContext(
            biz_type=biz_type,
            biz_id=_to_int(biz_id),
            attempt_no=_to_int(attempt_no) or 0,
        )
    )


def reset_llm_context(token: contextvars.Token) -> None:
    """恢复调用 set_llm_context 之前的上下文，避免维度泄漏到后续任务。"""
    LLM_CONTEXT.reset(token)


def current_llm_context() -> LlmContext:
    """读取当前埋点归因维度；不在任何入口作用域内时返回空上下文。"""
    return LLM_CONTEXT.get() or LlmContext()


def _to_int(value: Any) -> int | None:
    """把业务 ID 宽松归一为整型；无法转换时返回 None。"""
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None
