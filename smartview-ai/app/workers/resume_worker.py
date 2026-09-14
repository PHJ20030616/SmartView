"""简历解析 MQ worker。

该模块同时保留可独立调用的任务处理函数和 RabbitMQ 消费入口：
前者便于单元测试，后者负责真正把队列消息交给解析流程并回传结果。
"""

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
from app.core.llm_context import reset_llm_context, set_llm_context
from app.core.logging import configure_logging
from app.core.trace import reset_trace_id, resolve_trace_id, set_trace_id
from app.schemas.resume import ResumeParseResult, ResumeParseTask
from app.services.deepseek_client import close_shared_clients
from app.services.resume_parser import parse_resume

log = logging.getLogger(__name__)

PublishPayload = Callable[[dict[str, Any]], Awaitable[None]]

# 可重试的确定性错误码集合：只有"重发同一份请求有可能得到不同结果"的失败才在这里。
# - LLM_REQUEST_FAILED：传输层异常（超时/连接失败）；
# - LLM_RATE_LIMITED / LLM_UPSTREAM_ERROR：429 与 5xx，上游瞬时状态；
# - LLM_INVALID_JSON / LLM_SCHEMA_INVALID：内容不合法，重发有机会成功；
# - OCR_FAILED / RESUME_DOWNLOAD_*：外部依赖的短暂故障，下载与识别可重试。
# 刻意不含 LLM_REQUEST_REJECTED：其余 4xx 是请求本身被拒（模型名错误、缺鉴权头、
# 会话 ID 非法），原样重发只会重复失败；简历解析场景尤其明显——每次重试都要
# 重新下载 PDF 并重跑文本提取，重试一个永远不会成功的请求纯属浪费。
_RETRYABLE_APP_ERROR_CODES = {
    "LLM_REQUEST_FAILED",
    "LLM_RATE_LIMITED",
    "LLM_UPSTREAM_ERROR",
    "LLM_INVALID_JSON",
    "LLM_SCHEMA_INVALID",
    "OCR_FAILED",
    "RESUME_DOWNLOAD_FAILED",
    "RESUME_DOWNLOAD_TIMEOUT",
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


def _serialize_result(result: ResumeParseResult) -> dict[str, Any]:
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
    task: ResumeParseTask,
    error_message: str,
    *,
    retry_count: int | None = None,
) -> dict[str, Any]:
    """构造最终失败结果，确保 Spring 端不会永久等待 PENDING。"""
    return _serialize_result(
        ResumeParseResult(
            taskId=task.taskId,
            traceId=task.traceId,
            messageType="RESUME_PARSE_RESULT",
            schemaVersion="1.0.0",
            # 不可重试错误必须回传耗尽后的次数，使 Spring 将任务收敛为 FAILED，
            # 而不是依据旧次数继续标记为 RETRYING 并触发前端无效轮询。
            retryCount=task.retryCount if retry_count is None else retry_count,
            createdAt=datetime.now(timezone.utc),
            resumeFileId=task.resumeFileId,
            success=False,
            rawText="",
            errorMessage=error_message,
        )
    )


def _build_invalid_task_failure_result(payload: Any) -> dict[str, Any] | None:
    """为仍可关联业务任务的坏消息回传终态，避免 Spring 端永久等待。

    消息字段校验失败时不能继续执行 PDF 解析，但只要 taskId、traceId 和
    resumeFileId 仍然完整，就必须通知 Spring 将该任务收敛为失败。完全缺少
    关联字段的消息没有安全的归属目标，只能拒绝并交由死信/运维排查。
    """
    if not isinstance(payload, dict):
        return None

    try:
        result = ResumeParseResult(
            taskId=payload["taskId"],
            traceId=payload["traceId"],
            messageType="RESUME_PARSE_RESULT",
            schemaVersion="1.0.0",
            retryCount=payload.get("retryCount", 0),
            createdAt=datetime.now(timezone.utc),
            resumeFileId=payload["resumeFileId"],
            success=False,
            rawText="",
            errorMessage="简历解析任务消息格式无效，请重新上传简历",
        )
    except (KeyError, TypeError, ValueError, ValidationError):
        return None

    return _serialize_result(result)


def _build_retry_payload(task: ResumeParseTask) -> dict[str, Any]:
    """递增重试次数后重新投递，避免 nack(requeue=true) 造成无限重试。"""
    payload = task.model_dump(mode="json")
    payload["retryCount"] = task.retryCount + 1
    return payload


def _is_retryable_app_error(error: AppError) -> bool:
    """只重试外部依赖的短暂故障，配置和输入错误应立即结束任务。"""
    return error.code in _RETRYABLE_APP_ERROR_CODES


async def process_resume_parse_task(payload: dict[str, Any]) -> dict[str, Any]:
    """校验 MQ 信封并复用与 HTTP API 相同的简历解析流程。

    解析异常在达到最大重试次数前继续向消费框架抛出；
    最后一轮返回符合契约的失败消息，避免任务一直停留在 PENDING。
    """
    task = ResumeParseTask.model_validate(payload)
    # 把消息携带的 traceId 注入日志上下文，使解析流程内的所有日志自动携带 trace_id
    token = set_trace_id(str(task.traceId))
    # 埋点业务维度：简历文件 ID 用于按文件归因成本；retryCount 落进 attempt_no，
    # 让"同一个提示词被重试多次"在看板上可读。简历解析的每次重试都要重新下载
    # PDF 并重跑文本提取，这个维度正是判断"重试是否值得"的依据。
    llm_token = set_llm_context(
        biz_type="resume_file",
        biz_id=task.resumeFileId,
        attempt_no=task.retryCount,
    )
    try:
        log.info(
            "收到简历解析任务 taskId=%s resumeFileId=%s retryCount=%s",
            task.taskId,
            task.resumeFileId,
            task.retryCount,
        )
        try:
            response = await parse_resume(
                file_url=str(task.fileUrl),
                mime_type=task.mimeType,
                trace_id=str(task.traceId),
                raise_on_error=True,
            )
        except AppError as exc:
            if task.retryCount < 3:
                raise
            return _build_failure_result(task, exc.message)

        return _serialize_result(
            ResumeParseResult(
                taskId=task.taskId,
                traceId=task.traceId,
                messageType="RESUME_PARSE_RESULT",
                schemaVersion="1.0.0",
                retryCount=task.retryCount,
                createdAt=datetime.now(timezone.utc),
                resumeFileId=task.resumeFileId,
                **response.model_dump(),
            )
        )
    finally:
        reset_llm_context(llm_token)
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


async def handle_resume_parse_message(
    message: AbstractIncomingMessage,
    publish_payload: PublishPayload,
    settings: Settings,
) -> None:
    """处理单条 MQ 消息，并负责确认、重试或结果发布。"""
    payload: Any = None
    try:
        payload = json.loads(message.body)
        task = ResumeParseTask.model_validate(payload)
    except (json.JSONDecodeError, ValidationError) as exc:
        failure_result = _build_invalid_task_failure_result(payload)
        if failure_result is not None:
            # 保留可追踪字段时优先回传失败结果，Spring 才能终结关联任务和前端轮询。
            await publish_payload(failure_result)
            await message.ack()
            log.error(
                "简历解析任务消息格式无效，已回传终态失败结果，taskId=%s，error=%s",
                failure_result["taskId"],
                exc,
            )
            return

        # 完全无法关联业务任务的脏消息不能安全地修改数据库，只能拒绝后供运维排查。
        log.error("简历解析任务消息格式无效且无法关联任务，拒绝消息，error=%s", exc)
        await message.reject(requeue=False)
        return

    try:
        result = await process_resume_parse_task(task.model_dump(mode="json"))
    except AppError as exc:
        if not _is_retryable_app_error(exc):
            # URL 白名单、文件格式和缺少密钥等错误不会自行恢复，立即回传最终状态，
            # 避免前端继续轮询且 Spring 为同一错误反复投递任务。
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
            await publish_payload(retry_payload)
            await message.ack()
            log.warning(
                "简历解析任务将重试，taskId=%s, resumeFileId=%s, retryCount=%s",
                task.taskId,
                task.resumeFileId,
                retry_payload["retryCount"],
            )
            return
    except Exception as exc:
        # 非业务异常通常是临时网络或依赖故障，同样纳入有限重试。
        if task.retryCount < settings.rabbitmq_task_max_retries:
            await asyncio.sleep(
                settings.rabbitmq_retry_delay_seconds * (2**task.retryCount)
            )
            retry_payload = _build_retry_payload(task)
            await publish_payload(retry_payload)
            await message.ack()
            log.exception(
                "简历解析任务处理异常，将重试，taskId=%s, retryCount=%s",
                task.taskId,
                retry_payload["retryCount"],
            )
            return
        result = _build_failure_result(task, f"简历解析服务异常：{exc}")

    await publish_payload(result)
    await message.ack()
    log.info(
        "简历解析结果发布成功，taskId=%s, resumeFileId=%s, success=%s",
        task.taskId,
        task.resumeFileId,
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
        queue = await channel.declare_queue(
            settings.rabbitmq_resume_parse_queue,
            durable=True,
        )
        await queue.bind(
            exchange,
            routing_key=settings.rabbitmq_resume_parse_routing_key,
        )

        async def publish_payload(payload: dict[str, Any]) -> None:
            routing_key = (
                settings.rabbitmq_resume_result_routing_key
                if payload.get("messageType") == "RESUME_PARSE_RESULT"
                else settings.rabbitmq_resume_parse_routing_key
            )
            await _publish_json(exchange, routing_key, payload)

        log.info(
            "简历解析 worker 已启动，queue=%s, prefetch=%s",
            settings.rabbitmq_resume_parse_queue,
            settings.rabbitmq_prefetch_count,
        )
        async with queue.iterator() as queue_iterator:
            async for message in queue_iterator:
                # 整条消息处理期间注入 traceId 上下文，使格式校验、重试、
                # 结果发布与异常日志都能自动携带 trace_id（坏消息尽力提取）
                token = set_trace_id(_extract_message_trace_id(message.body))
                try:
                    await handle_resume_parse_message(
                        message,
                        publish_payload,
                        settings,
                    )
                except Exception:
                    # 发布或确认异常时不能显式 requeue 原消息：若“新消息已发布但 ACK
                    # 失败”，旧消息会以未递增的 retryCount 再次入队，导致重复解析甚至
                    # 无限循环。拒绝原消息后，由 Spring 的 stale-PENDING 补偿调度按
                    # 数据库重试预算重新生成任务；连接已断开而 nack 本身失败时，Broker
                    # 仍会按 AMQP 语义重新投递未确认消息。
                    log.exception("简历解析 MQ 消息处理异常，等待 Spring 补偿调度重新投递")
                    try:
                        await message.nack(requeue=False)
                    except Exception:
                        log.exception("简历解析 MQ 消息拒绝失败，Broker 将在连接断开后重新处理")
                finally:
                    reset_trace_id(token)
    finally:
        await connection.close()


async def run_resume_parse_worker(settings: Settings | None = None) -> None:
    """持续运行解析 worker，RabbitMQ 暂不可用时自动退避重连。"""
    settings = settings or get_settings()
    try:
        while True:
            try:
                await _consume_once(settings)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception(
                    "RabbitMQ 连接或消费循环异常，%s 秒后重试",
                    settings.rabbitmq_reconnect_delay_seconds,
                )
                await asyncio.sleep(settings.rabbitmq_reconnect_delay_seconds)
    finally:
        # 退出前关闭进程级共享 HTTP 客户端；必须在事件循环内部完成，
        # 因为 AsyncClient 绑定创建它的事件循环，跨循环关闭会报错。
        await close_shared_clients()


def main() -> None:
    """worker 命令行入口：python -m app.workers.resume_worker。"""
    settings = get_settings()
    configure_logging(
        settings.log_level,
        log_dir=settings.log_dir,
        log_file_enabled=settings.log_file_enabled,
        log_file_max_bytes=settings.log_file_max_bytes,
        log_file_backup_count=settings.log_file_backup_count,
        log_file_name="resume-worker.log",
    )
    try:
        asyncio.run(run_resume_parse_worker(settings))
    except KeyboardInterrupt:
        log.info("简历解析 worker 已停止")


if __name__ == "__main__":
    main()
