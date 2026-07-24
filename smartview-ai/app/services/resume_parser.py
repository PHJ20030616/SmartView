"""基于 LangGraph 的简历 PDF 解析服务。"""

from __future__ import annotations

import asyncio
import http.client
import ipaddress
import json
import logging
import re
import socket
import ssl
import time
from math import ceil
from functools import lru_cache
from typing import Any, TypedDict
from urllib.parse import urljoin, urlparse

import fitz
import httpx
from langgraph.graph import END, START, StateGraph
from pydantic import ValidationError

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.schemas.resume import (
    ParseResumeResponse,
    ResumeStructuredData,
)

log = logging.getLogger(__name__)

_REDIRECT_STATUS_CODES = {301, 302, 303, 307, 308}
_NON_MEANINGFUL_TEXT = re.compile(r"^[\W_]+$", re.UNICODE)
_PAGE_MARKER = re.compile(
    r"(?:page\s*\d+|\d+\s*of\s*\d+|第\s*\d+\s*页|共\s*\d+\s*页)",
    re.IGNORECASE,
)
_CONTENT_MARKER = re.compile(
    r"(电话|手机|邮箱|教育|工作|项目|技能|经历|经验|phone|email|education|work|project|skill)",
    re.IGNORECASE,
)


class ResumeParserState(TypedDict, total=False):
    """LangGraph 节点之间传递的状态。"""

    file_url: str
    mime_type: str
    trace_id: str
    pdf_bytes: bytes
    page_texts: list[str]
    ocr_page_indexes: list[int]
    ocr_texts: dict[int, str]
    raw_text: str
    structured_data: dict[str, Any]
    result: ParseResumeResponse
    error_message: str


def _raise_if_error(state: ResumeParserState) -> None:
    """节点失败后统一终止后续处理，保持错误信息可读。"""
    if state.get("error_message"):
        raise AppError(
            state["error_message"],
            code="RESUME_PARSE_FAILED",
            status_code=500,
        )


def _parse_download_url(file_url: str, settings: Settings):
    """校验 URL 语法和存储域名白名单，返回后续连接使用的解析结果。"""
    parsed = urlparse(file_url)
    hostname = (parsed.hostname or "").rstrip(".").lower()
    if parsed.scheme not in {"http", "https"} or not hostname or parsed.username or parsed.password:
        raise AppError("简历文件地址无效，仅支持不带用户信息的 HTTP(S) 地址", code="RESUME_URL_INVALID")

    allowed_hosts = {
        host.strip().rstrip(".").lower()
        for host in settings.resume_allowed_hosts
        if host.strip()
    }
    is_allowed_host = any(
        hostname == allowed or hostname.endswith(f".{allowed}")
        for allowed in allowed_hosts
    )
    if allowed_hosts and not is_allowed_host:
        raise AppError("简历文件地址不在允许的存储域名范围内", code="RESUME_URL_NOT_ALLOWED")
    # 公网地址必须使用 HTTPS；仅显式信任的内部存储主机可以使用 HTTP。
    if parsed.scheme == "http" and not is_allowed_host:
        raise AppError(
            "简历文件地址必须使用 HTTPS，内部 HTTP 存储需先加入域名白名单",
            code="RESUME_INSECURE_URL",
        )
    return parsed, hostname


def _validate_download_url(file_url: str, settings: Settings) -> str:
    """校验简历下载地址，阻断常见 SSRF 入口和不受控的重定向。"""
    parsed, hostname = _parse_download_url(file_url, settings)

    allowed_hosts = {
        host.strip().rstrip(".").lower()
        for host in settings.resume_allowed_hosts
        if host.strip()
    }
    allow_private = any(
        hostname == allowed or hostname.endswith(f".{allowed}")
        for allowed in allowed_hosts
    )
    _resolve_public_download_ip(hostname, parsed.port, allow_private=allow_private)
    return file_url


def _resolve_public_download_ip(
    hostname: str,
    port: int | None,
    *,
    allow_private: bool = False,
) -> str:
    """只解析一次并返回固定公网 IP，后续连接必须使用该 IP 防止 DNS rebinding。"""
    try:
        addresses = [
            ipaddress.ip_address(info[4][0])
            for info in socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM)
        ]
    except (OSError, ValueError) as exc:
        raise AppError("简历文件地址无法解析，请检查存储服务配置", code="RESUME_URL_INVALID") from exc

    for address in addresses:
        # 只有显式配置的存储域名才允许访问内网地址；未配置白名单时始终只允许公网地址。
        if address.is_global or (
            allow_private and (address.is_private or address.is_loopback)
        ):
            return str(address)
    raise AppError("简历文件地址解析到了不允许访问的内网地址", code="RESUME_URL_BLOCKED")


class _PinnedHTTPConnection(http.client.HTTPConnection):
    """使用已校验 IP 建立 HTTP 连接，同时保留原始 Host 头。"""

    def __init__(self, host: str, pinned_ip: str, port: int | None, timeout: float) -> None:
        super().__init__(host, port=port, timeout=timeout)
        self._pinned_ip = pinned_ip

    def connect(self) -> None:
        self.sock = socket.create_connection((self._pinned_ip, self.port), self.timeout)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    """使用已校验 IP 建立 HTTPS 连接，并用原始域名完成 TLS SNI。"""

    def __init__(self, host: str, pinned_ip: str, port: int | None, timeout: float) -> None:
        super().__init__(host, port=port, timeout=timeout, context=ssl.create_default_context())
        self._pinned_ip = pinned_ip

    def connect(self) -> None:
        self.sock = socket.create_connection((self._pinned_ip, self.port), self.timeout)
        self.sock = self._context.wrap_socket(self.sock, server_hostname=self.host)


def _download_pdf_sync(file_url: str, settings: Settings) -> dict[str, Any]:
    """在线程中执行固定 IP 的同步下载，避免 HTTP 客户端重新解析 DNS。"""
    current_url = file_url
    deadline = time.monotonic() + settings.resume_download_timeout_seconds
    for redirect_count in range(settings.resume_max_redirects + 1):
        parsed, hostname = _parse_download_url(current_url, settings)
        allowed_hosts = {
            host.strip().rstrip(".").lower()
            for host in settings.resume_allowed_hosts
            if host.strip()
        }
        allow_private = any(
            hostname == allowed or hostname.endswith(f".{allowed}")
            for allowed in allowed_hosts
        )
        pinned_ip = _resolve_public_download_ip(
            hostname,
            parsed.port,
            allow_private=allow_private,
        )
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        connection_class = (
            _PinnedHTTPSConnection if parsed.scheme == "https" else _PinnedHTTPConnection
        )
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise AppError(
                "简历文件下载超时，请稍后重试",
                code="RESUME_DOWNLOAD_TIMEOUT",
                status_code=504,
            )
        connection = connection_class(hostname, pinned_ip, port, min(remaining, 10.0))
        host_header = hostname
        if parsed.port and parsed.port != (443 if parsed.scheme == "https" else 80):
            host_header = f"{hostname}:{parsed.port}"
        request_path = parsed.path or "/"
        if parsed.query:
            request_path += f"?{parsed.query}"

        try:
            connection.request("GET", request_path, headers={"Host": host_header})
            response = connection.getresponse()
            if response.status in _REDIRECT_STATUS_CODES:
                location = response.getheader("Location")
                discarded = 0
                while True:
                    if time.monotonic() >= deadline:
                        raise AppError(
                            "简历文件下载超时，请稍后重试",
                            code="RESUME_DOWNLOAD_TIMEOUT",
                            status_code=504,
                        )
                    chunk = response.read(64 * 1024)
                    if time.monotonic() >= deadline:
                        raise AppError(
                            "简历文件下载超时，请稍后重试",
                            code="RESUME_DOWNLOAD_TIMEOUT",
                            status_code=504,
                        )
                    if not chunk:
                        break
                    discarded += len(chunk)
                    if discarded > settings.resume_max_file_bytes:
                        raise AppError(
                            "简历文件重定向响应过大，无法安全处理",
                            code="RESUME_REDIRECT_TOO_LARGE",
                            status_code=502,
                        )
                if not location:
                    raise AppError(
                        "简历文件下载地址重定向信息不完整",
                        code="RESUME_REDIRECT_INVALID",
                        status_code=502,
                    )
                if redirect_count >= settings.resume_max_redirects:
                    raise AppError(
                        "简历文件重定向次数超过限制",
                        code="RESUME_TOO_MANY_REDIRECTS",
                        status_code=502,
                    )
                current_url = urljoin(current_url, location)
                continue

            if response.status < 200 or response.status >= 300:
                raise AppError(
                    "简历文件下载失败，请检查文件地址是否有效",
                    code="RESUME_DOWNLOAD_FAILED",
                    status_code=502,
                )
            content_length = response.getheader("Content-Length")
            if content_length and int(content_length) > settings.resume_max_file_bytes:
                raise AppError("简历文件超过大小限制，无法解析", code="RESUME_FILE_TOO_LARGE")

            chunks: list[bytes] = []
            total = 0
            while True:
                if time.monotonic() >= deadline:
                    raise AppError(
                        "简历文件下载超时，请稍后重试",
                        code="RESUME_DOWNLOAD_TIMEOUT",
                        status_code=504,
                    )
                chunk = response.read(64 * 1024)
                if time.monotonic() >= deadline:
                    raise AppError(
                        "简历文件下载超时，请稍后重试",
                        code="RESUME_DOWNLOAD_TIMEOUT",
                        status_code=504,
                    )
                if not chunk:
                    break
                total += len(chunk)
                if total > settings.resume_max_file_bytes:
                    raise AppError("简历文件超过大小限制，无法解析", code="RESUME_FILE_TOO_LARGE")
                chunks.append(chunk)
            return {"pdf_bytes": b"".join(chunks)}
        finally:
            connection.close()
    raise AppError("简历文件下载失败，请稍后重试", code="RESUME_DOWNLOAD_FAILED", status_code=502)


async def _download_pdf(state: ResumeParserState, settings: Settings) -> dict[str, Any]:
    """下载带签名的 PDF，并在每次重定向前重新校验目标地址。"""
    try:
        return await asyncio.to_thread(_download_pdf_sync, state["file_url"], settings)
    except AppError:
        raise
    except socket.timeout as exc:
        raise AppError(
            "简历文件下载超时，请稍后重试",
            code="RESUME_DOWNLOAD_TIMEOUT",
            status_code=504,
        ) from exc
    except (OSError, http.client.HTTPException, ValueError) as exc:
        log.warning("简历文件下载失败 trace_id=%s error=%s", state["trace_id"], exc)
        raise AppError("简历文件下载失败，请检查文件地址是否有效", code="RESUME_DOWNLOAD_FAILED", status_code=502) from exc


def _extract_page_texts(pdf_bytes: bytes, settings: Settings) -> list[str]:
    """提取每页文本；页数上限用于防止异常 PDF 消耗过多资源。"""
    try:
        with fitz.open(stream=pdf_bytes, filetype="pdf") as document:
            if document.page_count == 0:
                raise AppError("PDF 文件没有可解析的页面", code="RESUME_EMPTY_PDF")
            if document.page_count > settings.resume_max_pages:
                raise AppError("PDF 页数超过解析限制，无法处理", code="RESUME_TOO_MANY_PAGES")
            return [page.get_text("text").strip() for page in document]
    except AppError:
        raise
    except (fitz.FileDataError, RuntimeError) as exc:
        raise AppError("PDF 文件损坏或格式无法识别", code="RESUME_INVALID_PDF") from exc


def _useful_text(text: str, settings: Settings) -> bool:
    """判断文本层是否足够可信，避免乱码或重复页码跳过 OCR。"""
    compact = "".join(text.split())
    if len(compact) < settings.resume_min_useful_page_characters:
        return False
    if _NON_MEANINGFUL_TEXT.fullmatch(compact):
        return False
    if _PAGE_MARKER.search(compact) and not _CONTENT_MARKER.search(compact):
        tokens = re.findall(r"[a-z]+|[\u4e00-\u9fff]+", compact.lower())
        if len(tokens) <= 8:
            return False

    meaningful_characters = sum(
        character.isalnum() or "\u4e00" <= character <= "\u9fff"
        for character in compact
    )
    if meaningful_characters < settings.resume_min_useful_page_characters:
        return False

    # 文本层中同一个字符反复出现，通常是扫描 PDF 的乱码占位符而不是简历内容。
    if len(compact) >= 20 and len(set(compact)) <= max(3, len(compact) // 10):
        return False
    return meaningful_characters / len(compact) >= 0.5


def _render_page(pdf_bytes: bytes, page_index: int, dpi: int, settings: Settings) -> Any:
    """把指定 PDF 页面渲染为 PaddleOCR 可接受的 RGB 数组。"""
    with fitz.open(stream=pdf_bytes, filetype="pdf") as document:
        page = document.load_page(page_index)
        page_width = page.rect.width * dpi / 72
        page_height = page.rect.height * dpi / 72
        if (
            page_width > settings.resume_max_page_dimension
            or page_height > settings.resume_max_page_dimension
            or ceil(page_width) * ceil(page_height) > settings.resume_max_page_pixels
        ):
            raise AppError(
                "PDF 页面尺寸过大，无法安全执行 OCR",
                code="RESUME_PAGE_TOO_LARGE",
                status_code=413,
            )
        import numpy as np

        pixmap = page.get_pixmap(dpi=dpi, alpha=False)
        return np.frombuffer(pixmap.samples, dtype=np.uint8).reshape(
            pixmap.height,
            pixmap.width,
            pixmap.n,
        )


def _read_paddle_result(result: Any) -> str:
    """兼容 PaddleOCR 3.x predict 与旧版 ocr 的返回结构。"""
    texts: list[str] = []
    try:
        items = list(result) if not isinstance(result, (str, bytes, dict)) else [result]
    except TypeError:
        items = [result]
    for item in items:
        # PaddleOCR 3.x Result 对象支持以字典方式直接读取 rec_texts。
        try:
            values = item["rec_texts"]
        except (KeyError, TypeError, IndexError):
            values = None
        if values:
            texts.extend(str(value) for value in values if str(value).strip())
            continue

        payload: Any = item
        if hasattr(item, "json"):
            try:
                payload = item.json
            except Exception:  # noqa: BLE001 - OCR SDK 的 json 可能是属性或方法
                payload = None
        if callable(payload):
            payload = payload()
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except json.JSONDecodeError:
                continue
        if not isinstance(payload, dict):
            # PaddleOCR 2.x: 每页结果为 [[box, [text, score]], ...]。
            if isinstance(item, list):
                for line in item:
                    try:
                        texts.append(str(line[1][0]))
                    except (IndexError, TypeError):
                        continue
            continue
        recognition = payload.get("res", payload)
        values = recognition.get("rec_texts", []) if isinstance(recognition, dict) else []
        texts.extend(str(value) for value in values if str(value).strip())
    return "\n".join(texts)


@lru_cache(maxsize=1)
def _get_paddle_ocr() -> Any:
    """延迟加载并缓存 OCR 引擎，避免每页重复加载模型。"""
    try:
        from paddleocr import PaddleOCR
    except ImportError as exc:
        raise AppError(
            "当前 PDF 缺少可用文本层，且服务未安装 PaddleOCR 依赖",
            code="OCR_DEPENDENCY_MISSING",
            status_code=503,
        ) from exc
    return PaddleOCR(
        lang="ch",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
    )


def _ocr_page(pdf_bytes: bytes, page_index: int, settings: Settings) -> str:
    """按需初始化 PaddleOCR，避免文本型 PDF 也承担 OCR 模型启动成本。"""
    try:
        ocr = _get_paddle_ocr()
        image = _render_page(pdf_bytes, page_index, settings.resume_ocr_dpi, settings)
        if hasattr(ocr, "predict"):
            return _read_paddle_result(ocr.predict(image))
        return _read_paddle_result(ocr.ocr(image, cls=True))
    except AppError:
        raise
    except Exception as exc:  # noqa: BLE001 - SDK 版本差异需要统一转为可读错误
        log.exception("PaddleOCR 执行失败 page=%s", page_index)
        raise AppError("简历图片文字识别失败，请稍后重试", code="OCR_FAILED", status_code=503) from exc


async def _extract_text(state: ResumeParserState, settings: Settings) -> dict[str, Any]:
    _raise_if_error(state)
    return {"page_texts": await asyncio.to_thread(_extract_page_texts, state["pdf_bytes"], settings)}


def _select_ocr_pages(state: ResumeParserState, settings: Settings) -> dict[str, Any]:
    _raise_if_error(state)
    page_texts = state.get("page_texts", [])
    return {
        "ocr_page_indexes": [
            index for index, text in enumerate(page_texts) if not _useful_text(text, settings)
        ]
    }


def _ocr_route(state: ResumeParserState) -> str:
    """根据页面文本质量选择 OCR 分支或直接合并文本。"""
    return "ocr_pages" if state.get("ocr_page_indexes") else "merge_text"


async def _ocr_pages(state: ResumeParserState, settings: Settings) -> dict[str, Any]:
    _raise_if_error(state)
    ocr_texts: dict[int, str] = {}
    for page_index in state.get("ocr_page_indexes", []):
        ocr_texts[page_index] = await asyncio.to_thread(
            _ocr_page,
            state["pdf_bytes"],
            page_index,
            settings,
        )
    return {"ocr_texts": ocr_texts}


def _merge_text(state: ResumeParserState) -> dict[str, Any]:
    _raise_if_error(state)
    pages = state.get("page_texts", [])
    ocr_texts = state.get("ocr_texts", {})
    merged = "\n\n".join(
        (ocr_texts.get(index) or text).strip()
        for index, text in enumerate(pages)
        if (ocr_texts.get(index) or text).strip()
    ).strip()
    if not merged:
        raise AppError("未能从 PDF 中识别到简历文本", code="RESUME_TEXT_EMPTY")
    return {"raw_text": merged}


def _build_llm_messages(raw_text: str, repair_error: str | None = None) -> list[dict[str, str]]:
    """提示词固定输出字段，降低中文简历中 LLM 漏字段和改名的概率。"""
    system = (
        "你是专业的中文简历解析器。只能输出 JSON 对象，不要输出 Markdown 或解释。"
        "字段必须严格包含 candidateName、contactInfo、education、workExperience、"
        "projectExperience、skills。缺失信息使用 null 或空数组。"
        "contactInfo 只能包含 phone、email、location；education 包含 school、degree、"
        "major、startDate、endDate；workExperience 包含 company、position、startDate、"
        "endDate、description；projectExperience 包含 projectName、role、description、techStack。"
    )
    user = (
        "请从下面的简历文本提取结构化信息。rawText 由服务端根据原始 PDF 文本可靠回填，"
        "不需要在 JSON 中重复输出原文：\n\n"
        f"{raw_text}"
    )
    if repair_error:
        user += f"\n\n上一次 JSON 校验失败，错误是：{repair_error}。请重新输出符合字段要求的 JSON。"
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def _parse_json_content(content: Any) -> dict[str, Any]:
    """兼容模型偶尔包裹 ```json 代码围栏的情况，同时禁止非对象结果。"""
    if not isinstance(content, str) or not content.strip():
        raise AppError(
            "模型返回的简历 JSON 为空或格式无效",
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
        start, end = cleaned.find("{"), cleaned.rfind("}")
        if start < 0 or end <= start:
            raise AppError("模型返回的简历 JSON 格式无效", code="LLM_INVALID_JSON", status_code=502)
        try:
            value = json.loads(cleaned[start : end + 1])
        except json.JSONDecodeError as exc:
            raise AppError("模型返回的简历 JSON 格式无效", code="LLM_INVALID_JSON", status_code=502) from exc
    if not isinstance(value, dict):
        raise AppError("模型返回的简历结果不是 JSON 对象", code="LLM_INVALID_JSON", status_code=502)
    return value


async def _call_deepseek(
    raw_text: str,
    settings: Settings,
    trace_id: str,
    repair_error: str | None = None,
) -> dict[str, Any]:
    """调用 DeepSeek JSON 模式；API Key 缺失时给出明确配置错误。"""
    api_key = settings.deepseek_api_key.get_secret_value().strip()
    if not api_key:
        raise AppError("未配置 DeepSeek API Key，请检查 .env 配置", code="LLM_CONFIG_MISSING", status_code=503)
    # 长简历只截取前段送入模型，避免输入上下文和调用成本不受文件大小控制；
    # 完整原文会在结构化结果返回前由服务端写回 rawText。
    llm_text = raw_text[: settings.deepseek_max_input_characters]
    payload = {
        "model": settings.deepseek_model,
        "messages": _build_llm_messages(llm_text, repair_error),
        "temperature": settings.deepseek_temperature,
        "max_tokens": settings.deepseek_max_tokens,
        "response_format": {"type": "json_object"},
    }
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
            return _parse_json_content(content)
    except AppError:
        raise
    except (httpx.HTTPError, KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        log.exception("DeepSeek 简历结构化失败 trace_id=%s", trace_id)
        raise AppError("简历结构化服务暂时不可用，请稍后重试", code="LLM_REQUEST_FAILED", status_code=502) from exc


async def _structure_resume(state: ResumeParserState, settings: Settings) -> dict[str, Any]:
    _raise_if_error(state)
    raw_text = state["raw_text"]
    trace_id = state["trace_id"]
    try:
        payload = await _call_deepseek(raw_text, settings, trace_id)
    except AppError as first_error:
        if first_error.code != "LLM_INVALID_JSON":
            raise
        # JSON 模式偶尔会返回空内容或截断结果；仅针对这类可恢复错误重试一次。
        payload = await _call_deepseek(
            raw_text,
            settings,
            trace_id,
            repair_error=first_error.message,
        )
    # 原始文本由 PDF/OCR 节点提供，是可信源；不依赖模型重复输出，避免被截断或改写。
    payload["rawText"] = raw_text
    try:
        structured = ResumeStructuredData.model_validate(payload)
    except ValidationError as first_error:
        # 只做一次修复调用，避免模型异常时无限重试并放大成本。
        repaired_payload = await _call_deepseek(
            raw_text,
            settings,
            trace_id,
            repair_error=str(first_error),
        )
        repaired_payload["rawText"] = raw_text
        try:
            structured = ResumeStructuredData.model_validate(repaired_payload)
        except ValidationError as second_error:
            raise AppError("模型返回的简历字段无法校验，请稍后重试", code="LLM_SCHEMA_INVALID", status_code=502) from second_error
    return {"structured_data": structured.model_dump(), "result": ParseResumeResponse(success=True, **structured.model_dump())}


def build_resume_parser_graph(settings: Settings | None = None):
    """构建可被 API 和 worker 共同复用的 LangGraph 工作流。"""
    runtime_settings = settings or get_settings()

    async def download_node(state: ResumeParserState) -> dict[str, Any]:
        return await _download_pdf(state, runtime_settings)

    async def extract_node(state: ResumeParserState) -> dict[str, Any]:
        return await _extract_text(state, runtime_settings)

    async def ocr_node(state: ResumeParserState) -> dict[str, Any]:
        return await _ocr_pages(state, runtime_settings)

    async def structure_node(state: ResumeParserState) -> dict[str, Any]:
        return await _structure_resume(state, runtime_settings)

    graph = StateGraph(ResumeParserState)
    graph.add_node("download_pdf", download_node)
    graph.add_node("extract_text", extract_node)
    graph.add_node("select_ocr_pages", lambda state: _select_ocr_pages(state, runtime_settings))
    graph.add_node("ocr_pages", ocr_node)
    graph.add_node("merge_text", _merge_text)
    graph.add_node("structure_resume", structure_node)
    graph.add_edge(START, "download_pdf")
    graph.add_edge("download_pdf", "extract_text")
    graph.add_edge("extract_text", "select_ocr_pages")
    graph.add_conditional_edges("select_ocr_pages", _ocr_route)
    graph.add_edge("ocr_pages", "merge_text")
    graph.add_edge("merge_text", "structure_resume")
    graph.add_edge("structure_resume", END)
    return graph.compile()


@lru_cache
def get_resume_parser_graph():
    """缓存生产环境解析图，避免每次请求重复编译节点与边。"""
    return build_resume_parser_graph()


async def parse_resume(
    file_url: str,
    mime_type: str,
    trace_id: str,
    *,
    settings: Settings | None = None,
    raise_on_error: bool = False,
) -> ParseResumeResponse:
    """执行简历解析图，并将底层异常转换为接口可读的失败响应。"""
    if mime_type.lower() != "application/pdf":
        raise AppError("当前仅支持 application/pdf 格式的简历文件", code="UNSUPPORTED_FILE_TYPE")
    initial_state: ResumeParserState = {
        "file_url": file_url,
        "mime_type": mime_type,
        "trace_id": trace_id,
    }
    try:
        graph = build_resume_parser_graph(settings) if settings else get_resume_parser_graph()
        final_state = await graph.ainvoke(initial_state)
        return final_state["result"]
    except AppError as exc:
        if raise_on_error:
            raise
        return ParseResumeResponse(success=False, rawText="", errorMessage=exc.message)
    except Exception as exc:  # noqa: BLE001 - 对外隐藏内部堆栈，保留统一可读错误
        log.exception("简历解析流程异常 trace_id=%s", trace_id)
        if raise_on_error:
            raise AppError(
                "简历解析失败，请稍后重试",
                code="RESUME_PARSE_FAILED",
                status_code=500,
            ) from exc
        return ParseResumeResponse(success=False, rawText="", errorMessage="简历解析失败，请稍后重试")
