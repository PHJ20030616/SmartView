"""清理任务 MQ worker（Task 7.2 软删除与物理清理）。

消费 Spring Boot 在简历删除事务中投递的 CLEANUP_TASK，删除：
1. MinIO 对象（简历原始文件，按 objectKey 幂等删除）；
2. Chroma 向量（简历画像的全部历史版本切片，按画像 ID 幂等删除）。

执行完成后回传 CLEANUP_RESULT 供 Spring 更新 ai_task 终态（审计）。
外部依赖（MinIO/Chroma）暂时不可用按指数退避有界重试；重试耗尽回传失败结果。
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
from app.schemas.cleanup import CleanupResult, CleanupTask
from app.services.cleanup_service import cleanup_resume_resources

log = logging.getLogger(__name__)

PublishPayload = Callable[[dict[str, Any]], Awaitable[None]]

# 只有 MinIO/Chroma 等外部依赖暂时不可用才重试；消息字段缺失、业务类型不符
# 属于确定性业务错误，继续重试不会改变结果。
_RETRYABLE_APP_ERROR_CODES = {
    "MINIO_UNAVAILABLE",
    "VECTOR_STORE_UNAVAILABLE",
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


def _serialize_result(result: CleanupResult) -> dict[str, Any]:
    """序列化为可直接发布到 RabbitMQ 的 JSON 数据。"""
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


def _build_failure_result(
    task: CleanupTask,
    error_message: str,
    *,
    retry_count: int | None = None,
) -> dict[str, Any]:
    """构造终态失败结果，确保 Spring 不会把清理任务永久留在非终态。"""
    return _serialize_result(
        CleanupResult(
            taskId=task.taskId,
            traceId=task.traceId,
            messageType="CLEANUP_RESULT",
            schemaVersion="1.0.0",
            retryCount=task.retryCount if retry_count is None else retry_count,
            createdAt=datetime.now(timezone.utc),
            bizType=task.bizType,
            bizId=task.bizId,
            success=False,
            errorMessage=error_message,
        )
    )


def _build_invalid_task_failure_result(payload: Any) -> dict[str, Any] | None:
    """为仍可关联任务的坏消息生成失败结果。

    完全缺少 taskId、traceId 或业务关联字段的消息无法安全写回 MySQL，
    只能拒绝并交由 RabbitMQ 死信队列和运维处理。
    """
    if not isinstance(payload, dict):
        return None

    try:
        result = CleanupResult(
            taskId=payload["taskId"],
            traceId=payload["traceId"],
            messageType="CLEANUP_RESULT",
            schemaVersion="1.0.0",
            retryCount=payload.get("retryCount", 0),
            createdAt=datetime.now(timezone.utc),
            bizType=(
                payload.get("bizType")
                if payload.get("bizType") in {"RESUME_FILE"}
                else "RESUME_FILE"
            ),
            bizId=payload["bizId"],
            success=False,
            errorMessage="清理任务消息格式无效，请重试",
        )
    except (KeyError, TypeError, ValueError, ValidationError):
        return None

    return _serialize_result(result)


def _build_retry_payload(task: CleanupTask) -> dict[str, Any]:
    """递增重试次数后重新投递，避免 nack(requeue=true) 无限重复。"""
    payload = task.model_dump(mode="json")
    payload["retryCount"] = task.retryCount + 1
    return payload


def _is_retryable_app_error(error: AppError) -> bool:
    """只重试可恢复的外部依赖异常。"""
    return error.code in _RETRYABLE_APP_ERROR_CODES


async def process_cleanup_task(
    payload: dict[str, Any],
    settings: Settings | None = None,
) -> dict[str, Any]:
    """执行单个清理任务并返回符合结果契约的消息。"""
    task = CleanupTask.model_validate(payload)
    # 把消息携带的 traceId 注入日志上下文，使清理流程内的所有日志自动携带 trace_id
    token = set_trace_id(str(task.traceId))
    try:
        return _execute_cleanup_task(task, settings)
    finally:
        reset_trace_id(token)


def _execute_cleanup_task(
    task: CleanupTask,
    settings: Settings | None,
) -> dict[str, Any]:
    """执行清理逻辑；traceId 上下文由调用方负责注入与清理。"""
    log.info(
        "收到清理任务 taskId=%s bizType=%s bizId=%s objectKey=%s profiles=%s retryCount=%s",
        task.taskId,
        task.bizType,
        task.bizId,
        task.objectKey,
        len(task.resumeProfileIds),
        task.retryCount,
    )
    cleaned_count = cleanup_resume_resources(
        task.objectKey,
        task.resumeProfileIds,
        settings=settings,
    )
    return _serialize_result(
        CleanupResult(
            taskId=task.taskId,
            traceId=task.traceId,
            messageType="CLEANUP_RESULT",
            schemaVersion="1.0.0",
            retryCount=task.retryCount,
            createdAt=datetime.now(timezone.utc),
            bizType=task.bizType,
            bizId=task.bizId,
            success=True,
            cleanedProfileCount=cleaned_count,
        )
    )


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


async def handle_cleanup_message(
    message: AbstractIncomingMessage,
    publish_payload: PublishPayload,
    settings: Settings,
    publish_task_retry: PublishPayload,
) -> None:
    """处理单条清理任务消息，并负责确认、有限重试或结果发布。

    - publish_payload：发布结果消息（结果队列 routing key）
    - publish_task_retry：重试时把任务消息重新投递到任务队列。
      若误用 publish_payload，任务消息会进入结果队列，被 Spring 结果消费者
      按格式校验拒绝并进入 DLQ，清理任务会被错误标记为终态失败。
    """
    payload: Any = None
    try:
        payload = json.loads(message.body)
        task = CleanupTask.model_validate(payload)
    except (json.JSONDecodeError, ValidationError, TypeError, UnicodeDecodeError) as exc:
        failure_result = _build_invalid_task_failure_result(payload)
        if failure_result is not None:
            await publish_payload(failure_result)
            await message.ack()
            log.error(
                "清理任务消息格式无效，已回传终态失败结果，taskId=%s，error=%s",
                failure_result["taskId"],
                exc,
            )
            return

        log.error("清理任务消息格式无效且无法关联任务，拒绝消息，error=%s", exc)
        await message.reject(requeue=False)
        return

    try:
        result = await process_cleanup_task(
            task.model_dump(mode="json"),
            settings,
        )
    except AppError as exc:
        if not _is_retryable_app_error(exc):
            # 字段缺失、业务类型不符等错误是确定性失败，
            # 立即回传终态，避免清理任务无意义地重试。
            result = _build_failure_result(
                task,
                exc.message,
                retry_count=settings.rabbitmq_task_max_retries,
            )
        elif task.retryCount >= settings.rabbitmq_task_max_retries:
            result = _build_failure_result(task, exc.message)
        else:
            await asyncio.sleep(
                settings.rabbitmq_retry_delay_seconds * (2**task.retryCount)
            )
            retry_payload = _build_retry_payload(task)
            # 重试消息必须回到任务队列；发到结果队列会被 Spring 结果消费者拒绝
            await publish_task_retry(retry_payload)
            await message.ack()
            log.warning(
                "清理任务将重试，taskId=%s, objectKey=%s, retryCount=%s",
                task.taskId,
                task.objectKey,
                retry_payload["retryCount"],
            )
            return
    except Exception as exc:
        # 未预期异常通常来自网络、数据库连接或外部依赖，同样采用有界重试。
        if task.retryCount < settings.rabbitmq_task_max_retries:
            await asyncio.sleep(
                settings.rabbitmq_retry_delay_seconds * (2**task.retryCount)
            )
            retry_payload = _build_retry_payload(task)
            # 重试消息必须回到任务队列；发到结果队列会被 Spring 结果消费者拒绝
            await publish_task_retry(retry_payload)
            await message.ack()
            log.exception(
                "清理任务处理异常，将重试，taskId=%s, retryCount=%s",
                task.taskId,
                retry_payload["retryCount"],
            )
            return
        result = _build_failure_result(task, f"清理服务异常：{exc}")

    await publish_payload(result)
    await message.ack()
    log.info(
        "清理任务结果发布成功，taskId=%s, bizId=%s, success=%s",
        task.taskId,
        task.bizId,
        result["success"],
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
            settings.rabbitmq_cleanup_dead_letter_queue,
            durable=True,
        )
        await dead_letter_queue.bind(
            dead_letter_exchange,
            routing_key=settings.rabbitmq_cleanup_dead_letter_routing_key,
        )
        queue = await channel.declare_queue(
            settings.rabbitmq_cleanup_queue,
            durable=True,
            arguments={
                "x-dead-letter-exchange": settings.rabbitmq_dead_letter_exchange,
                "x-dead-letter-routing-key": (
                    settings.rabbitmq_cleanup_dead_letter_routing_key
                ),
            },
        )
        await queue.bind(
            exchange,
            routing_key=settings.rabbitmq_cleanup_routing_key,
        )

        async def publish_payload(payload: dict[str, Any]) -> None:
            await _publish_json(
                exchange,
                settings.rabbitmq_cleanup_result_routing_key,
                payload,
            )

        async def publish_task_payload(payload: dict[str, Any]) -> None:
            """重试任务消息发布到任务队列，避免被 Spring 结果消费者误判。"""
            await _publish_json(
                exchange,
                settings.rabbitmq_cleanup_routing_key,
                payload,
            )

        log.info(
            "清理 worker 已启动，queue=%s, prefetch=%s",
            settings.rabbitmq_cleanup_queue,
            settings.rabbitmq_prefetch_count,
        )
        async with queue.iterator() as queue_iterator:
            async for message in queue_iterator:
                # 整条消息处理期间注入 traceId 上下文，使格式校验、重试、
                # 结果发布与异常日志都能自动携带 trace_id（坏消息尽力提取）
                token = set_trace_id(_extract_message_trace_id(message.body))
                try:
                    await handle_cleanup_message(
                        message,
                        publish_payload,
                        settings,
                        publish_task_payload,
                    )
                except Exception:
                    # 发布或确认异常时不显式 requeue 原消息，避免新旧消息同时存在
                    # 且 retryCount 未递增；由 Spring 补偿调度或 Broker 断线重投接管。
                    log.exception("清理 MQ 消息处理异常，等待补偿调度重新投递")
                    try:
                        await message.nack(requeue=False)
                    except Exception:
                        log.exception("清理 MQ 消息拒绝失败，等待 Broker 断线重投")
                finally:
                    reset_trace_id(token)
    finally:
        await connection.close()


async def run_cleanup_worker(settings: Settings | None = None) -> None:
    """持续运行清理 worker，RabbitMQ 暂不可用时自动退避重连。"""
    settings = settings or get_settings()
    while True:
        try:
            await _consume_once(settings)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception(
                "RabbitMQ 连接或清理消费循环异常，%s 秒后重试",
                settings.rabbitmq_reconnect_delay_seconds,
            )
            await asyncio.sleep(settings.rabbitmq_reconnect_delay_seconds)


def main() -> None:
    """worker 命令行入口：python -m app.workers.cleanup_worker。"""
    settings = get_settings()
    configure_logging(
        settings.log_level,
        log_dir=settings.log_dir,
        log_file_enabled=settings.log_file_enabled,
        log_file_max_bytes=settings.log_file_max_bytes,
        log_file_backup_count=settings.log_file_backup_count,
        log_file_name="cleanup-worker.log",
    )
    try:
        asyncio.run(run_cleanup_worker(settings))
    except KeyboardInterrupt:
        log.info("清理 worker 已停止")


if __name__ == "__main__":
    main()
