"""报告生成 MQ worker。

该 worker 只消费 Spring Boot 创建的会话 ID，再从 MySQL 读取会话、已回答题目、
回答与评估、简历画像与画像分析，由 ReportGenerator 混合式生成报告并回传结果。
结果消息字段与 contracts/mq/report_generate_result.schema.json 保持一致。
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable
from urllib.parse import quote

import aio_pika
from aio_pika import DeliveryMode, ExchangeType, Message
from aio_pika.abc import AbstractIncomingMessage, AbstractRobustExchange
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.core.logging import configure_logging
from app.core.trace import reset_trace_id, resolve_trace_id, set_trace_id
from app.schemas.report import ReportGenerateResult, ReportGenerateTask
from app.services.report_generator import ReportGenerator

log = logging.getLogger(__name__)

PublishPayload = Callable[[dict[str, Any]], Awaitable[None]]

# 只有 LLM 服务短暂不可用或返回可修复的 JSON 时才重试；会话/报告缺失等确定性
# 业务错误继续重试不会改变结果。LLM_SCHEMA_INVALID 已在生成器内部做过一次修复，
# 仍失败即视为终态，避免 MQ 重试再重复调用 LLM。
_RETRYABLE_APP_ERROR_CODES = {
    "LLM_REQUEST_FAILED",
    "LLM_INVALID_JSON",
}


def _extract_message_trace_id(body: bytes) -> str:
    """从 MQ 消息体尽力提取 traceId 用于日志关联；坏消息返回占位符。"""
    try:
        payload = json.loads(body)
    except (json.JSONDecodeError, TypeError, UnicodeDecodeError):
        return "-"
    if isinstance(payload, dict) and payload.get("traceId"):
        return resolve_trace_id(str(payload["traceId"]))
    return "-"


def _serialize_result(result: ReportGenerateResult) -> dict[str, Any]:
    """序列化为可直接发布到 RabbitMQ 的 JSON 数据（content 由 worker 回填）。"""
    return result.model_dump(mode="json", exclude_none=True)


def build_amqp_url(settings: Settings) -> str:
    """根据拆分配置构造 AMQP URL，正确转义特殊字符。"""
    username = quote(settings.rabbitmq_username, safe="")
    password = quote(settings.rabbitmq_password.get_secret_value(), safe="")
    vhost = quote(settings.rabbitmq_vhost, safe="")
    return (
        f"amqp://{username}:{password}@"
        f"{settings.rabbitmq_host}:{settings.rabbitmq_port}/{vhost}"
    )


def _build_success_result(task: ReportGenerateTask, content: ReportGenerateResult) -> dict[str, Any]:
    """构造成功结果：envelope + 报告内容。

    注意展开顺序：必须先展开 content 的内容字段，再覆盖真实信封字段。content 来自
    ReportGenerator.generate，其 envelope 字段（taskId/traceId/sessionId 等）是占位默认值
    （随机 UUID、空 sessionId），若把 **content.model_dump() 放在信封字段之后，会覆盖
    task 的真实信封，导致 Spring 按 taskId/sessionId/traceId 无法关联结果，报告将一直
    停留在生成中。
    """
    return {
        **content.model_dump(mode="json", exclude_none=True),
        "taskId": str(task.taskId),
        "traceId": str(task.traceId),
        "messageType": "REPORT_GENERATE_RESULT",
        "schemaVersion": "1.0.0",
        "retryCount": task.retryCount,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sessionId": task.sessionId,
        "success": True,
    }


def _build_failure_result(
    task: ReportGenerateTask,
    error_message: str,
    *,
    retry_count: int | None = None,
) -> dict[str, Any]:
    """构造终态失败结果，确保 Spring 不会永久等待 PENDING。"""
    return {
        "taskId": str(task.taskId),
        "traceId": str(task.traceId),
        "messageType": "REPORT_GENERATE_RESULT",
        "schemaVersion": "1.0.0",
        "retryCount": task.retryCount if retry_count is None else retry_count,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sessionId": task.sessionId,
        "success": False,
        "errorMessage": error_message,
    }


def _build_invalid_task_failure_result(payload: Any) -> dict[str, Any] | None:
    """为仍可关联任务的坏消息生成失败结果；无法关联则拒绝进 DLQ。"""
    if not isinstance(payload, dict):
        return None
    try:
        return {
            "taskId": payload["taskId"],
            "traceId": payload["traceId"],
            "messageType": "REPORT_GENERATE_RESULT",
            "schemaVersion": "1.0.0",
            "retryCount": payload.get("retryCount", 0),
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "sessionId": payload["sessionId"],
            "success": False,
            "errorMessage": "报告生成任务消息格式无效，请重试",
        }
    except (KeyError, TypeError, ValueError, ValidationError):
        return None


def _build_retry_payload(task: ReportGenerateTask) -> dict[str, Any]:
    """递增重试次数后重新投递，避免 nack(requeue=true) 无限重复。"""
    payload = task.model_dump(mode="json")
    payload["retryCount"] = task.retryCount + 1
    return payload


def _is_retryable_app_error(error: AppError) -> bool:
    """只重试外部依赖的短暂故障，配置和输入错误应立即结束任务。"""
    return error.code in _RETRYABLE_APP_ERROR_CODES


async def process_report_generate_task(
    payload: dict[str, Any],
    settings: Settings | None = None,
) -> dict[str, Any]:
    """执行单个报告生成任务并返回符合结果契约的消息。"""
    task = ReportGenerateTask.model_validate(payload)
    # 把消息携带的 traceId 注入日志上下文，使生成流程内的所有日志自动携带 trace_id
    token = set_trace_id(str(task.traceId))
    try:
        log.info(
            "收到报告生成任务 taskId=%s sessionId=%s retryCount=%s",
            task.taskId, task.sessionId, task.retryCount,
        )
        content = await ReportGenerator(settings).generate(task.sessionId)
        return _build_success_result(task, content)
    finally:
        reset_trace_id(token)


async def _publish_json(
    exchange: AbstractRobustExchange,
    routing_key: str,
    payload: dict[str, Any],
) -> None:
    """发布持久化 JSON 消息，供 Spring 的 Jackson 消费者反序列化。"""
    await exchange.publish(
        Message(
            body=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            content_type="application/json",
            content_encoding="utf-8",
            delivery_mode=DeliveryMode.PERSISTENT,
            headers={
                "messageType": payload.get("messageType"),
                "traceId": payload.get("traceId"),
            },
        ),
        routing_key=routing_key,
    )


async def handle_report_generate_message(
    message: AbstractIncomingMessage,
    publish_payload: PublishPayload,
    settings: Settings,
    publish_task_retry: PublishPayload,
) -> None:
    """处理单条报告生成任务消息，并负责确认、有限重试或结果发布。

    - publish_payload：发布结果消息（结果队列 routing key）
    - publish_task_retry：重试时把任务消息重新投递到任务队列。
      若误用 publish_payload，任务消息会进入结果队列，被 Spring 结果消费者
      按格式校验拒绝并进入 DLQ。
    """
    payload: Any = None
    try:
        payload = json.loads(message.body)
        task = ReportGenerateTask.model_validate(payload)
    except (json.JSONDecodeError, ValidationError, TypeError, UnicodeDecodeError) as exc:
        failure_result = _build_invalid_task_failure_result(payload)
        if failure_result is not None:
            await publish_payload(failure_result)
            await message.ack()
            log.error(
                "报告生成任务消息格式无效，已回传终态失败结果，taskId=%s，error=%s",
                failure_result["taskId"], exc,
            )
            return
        log.error("报告生成任务消息格式无效且无法关联任务，拒绝消息，error=%s", exc)
        await message.reject(requeue=False)
        return

    try:
        result = await process_report_generate_task(task.model_dump(mode="json"), settings)
    except AppError as exc:
        if not _is_retryable_app_error(exc):
            # 会话/报告缺失等错误是确定性失败，立即回传终态，避免前端轮询无意义等待。
            result = _build_failure_result(task, exc.message)
        elif task.retryCount >= settings.rabbitmq_task_max_retries:
            result = _build_failure_result(task, exc.message)
        else:
            await asyncio.sleep(
                settings.rabbitmq_retry_delay_seconds * (2**task.retryCount)
            )
            retry_payload = _build_retry_payload(task)
            # 重试消息必须回到任务队列；发到结果队列会被 Spring 结果消费者拒绝。
            await publish_task_retry(retry_payload)
            await message.ack()
            log.warning(
                "报告生成任务将重试，taskId=%s, sessionId=%s, retryCount=%s",
                task.taskId, task.sessionId, retry_payload["retryCount"],
            )
            return
    except Exception as exc:
        # 未预期异常通常来自网络或数据库连接，同样采用有界重试。
        if task.retryCount < settings.rabbitmq_task_max_retries:
            await asyncio.sleep(
                settings.rabbitmq_retry_delay_seconds * (2**task.retryCount)
            )
            retry_payload = _build_retry_payload(task)
            await publish_task_retry(retry_payload)
            await message.ack()
            log.exception(
                "报告生成任务处理异常，将重试，taskId=%s, retryCount=%s",
                task.taskId, retry_payload["retryCount"],
            )
            return
        result = _build_failure_result(task, f"报告生成服务异常：{exc}")

    await publish_payload(result)
    await message.ack()
    log.info(
        "报告生成结果发布成功，taskId=%s, sessionId=%s, success=%s",
        task.taskId, task.sessionId, result["success"],
    )


async def _consume_once(settings: Settings) -> None:
    """建立一次 RabbitMQ 消费会话；连接断开后由外层循环重新建立。"""
    connection = await aio_pika.connect_robust(build_amqp_url(settings))
    try:
        channel = await connection.channel()
        await channel.set_qos(prefetch_count=settings.rabbitmq_prefetch_count)
        exchange = await channel.declare_exchange(
            settings.rabbitmq_exchange,
            type=ExchangeType.DIRECT,
            durable=True,
        )
        dead_letter_exchange = await channel.declare_exchange(
            settings.rabbitmq_dead_letter_exchange,
            type=ExchangeType.DIRECT,
            durable=True,
        )
        dead_letter_queue = await channel.declare_queue(
            settings.rabbitmq_report_generate_dead_letter_queue,
            durable=True,
        )
        await dead_letter_queue.bind(
            dead_letter_exchange,
            routing_key=settings.rabbitmq_report_generate_dead_letter_routing_key,
        )
        queue = await channel.declare_queue(
            settings.rabbitmq_report_generate_queue,
            durable=True,
            arguments={
                "x-dead-letter-exchange": settings.rabbitmq_dead_letter_exchange,
                "x-dead-letter-routing-key": (
                    settings.rabbitmq_report_generate_dead_letter_routing_key
                ),
            },
        )
        await queue.bind(
            exchange,
            routing_key=settings.rabbitmq_report_generate_routing_key,
        )

        async def publish_payload(payload: dict[str, Any]) -> None:
            await _publish_json(
                exchange,
                settings.rabbitmq_report_generate_result_routing_key,
                payload,
            )

        async def publish_task_payload(payload: dict[str, Any]) -> None:
            """重试任务消息发布到任务队列，避免被 Spring 结果消费者误判。"""
            await _publish_json(
                exchange,
                settings.rabbitmq_report_generate_routing_key,
                payload,
            )

        log.info(
            "报告生成 worker 已启动，queue=%s, prefetch=%s",
            settings.rabbitmq_report_generate_queue,
            settings.rabbitmq_prefetch_count,
        )
        async with queue.iterator() as queue_iterator:
            async for message in queue_iterator:
                token = set_trace_id(_extract_message_trace_id(message.body))
                try:
                    await handle_report_generate_message(
                        message,
                        publish_payload,
                        settings,
                        publish_task_payload,
                    )
                except Exception:
                    log.exception("报告生成 MQ 消息处理异常，等待补偿调度重新投递")
                    try:
                        await message.nack(requeue=False)
                    except Exception:
                        log.exception("报告生成 MQ 消息拒绝失败，等待 Broker 断线重投")
                finally:
                    reset_trace_id(token)
    finally:
        await connection.close()


async def run_report_generate_worker(settings: Settings | None = None) -> None:
    """持续运行报告生成 worker，RabbitMQ 暂不可用时自动退避重连。"""
    settings = settings or get_settings()
    while True:
        try:
            await _consume_once(settings)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception(
                "RabbitMQ 连接或报告生成消费循环异常，%s 秒后重试",
                settings.rabbitmq_reconnect_delay_seconds,
            )
            await asyncio.sleep(settings.rabbitmq_reconnect_delay_seconds)


def main() -> None:
    """worker 命令行入口：python -m app.workers.report_worker。"""
    settings = get_settings()
    configure_logging(
        settings.log_level,
        log_dir=settings.log_dir,
        log_file_enabled=settings.log_file_enabled,
        log_file_max_bytes=settings.log_file_max_bytes,
        log_file_backup_count=settings.log_file_backup_count,
        log_file_name="report-worker.log",
    )
    try:
        asyncio.run(run_report_generate_worker(settings))
    except KeyboardInterrupt:
        log.info("报告生成 worker 已停止")


if __name__ == "__main__":
    main()
