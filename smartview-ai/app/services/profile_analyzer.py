"""方向画像分析服务：基于已确认简历、简历向量片段、知识/面经检索生成画像分析。

画像分析是系统内部的面试准备材料，用来生成阶段计划和出题策略。
分析结果字段与契约（AnalyzeProfileResponse / profile_analyze_result.schema.json）一致。
"""

from __future__ import annotations

import json
import logging
from typing import Any, Mapping

import httpx
from pydantic import ValidationError
from sqlalchemy import Engine, text

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.retrievers.experience_retriever import retrieve_experience
from app.retrievers.knowledge_retriever import retrieve_knowledge
from app.retrievers.resume_retriever import retrieve_resume_context
from app.schemas.profile import ProfileAnalysis
from app.services.resume_vectorizer import build_mysql_engine

log = logging.getLogger(__name__)


class ProfileAnalyzer:
    """从 MySQL 读取已确认画像，结合向量检索与知识检索生成方向画像分析。"""

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        engine: Engine | Any | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.engine = engine or build_mysql_engine(self.settings)

    async def analyze(
        self,
        resume_profile_id: str,
        profile_version: int,
        role_direction: str,
    ) -> ProfileAnalysis:
        """生成指定方向的画像分析。"""
        profile = self._load_confirmed_profile(
            int(resume_profile_id),
            int(profile_version),
        )
        user_id = int(profile["user_id"])
        log.info(
            "开始生成画像分析 profile_id=%s version=%s direction=%s candidate=%s",
            resume_profile_id,
            profile_version,
            role_direction,
            profile.get("candidate_name"),
        )

        # 简历向量片段：Chroma 检索失败时 ResumeRetriever 自动降级为 MySQL 完整简历。
        # resume_retriever 对"画像不存在/未确认/不属于该用户"抛普通 ValueError，
        # 这里映射为确定性 AppError，避免 worker 把它当作可重试的未预期异常。
        try:
            resume_ctx = retrieve_resume_context(
                self._resume_query(role_direction),
                user_id=user_id,
                resume_profile_id=int(profile["id"]),
                top_k=self.settings.profile_analyze_resume_top_k,
                settings=self.settings,
            )
        except ValueError as exc:
            raise AppError(
                "简历画像不存在、未确认或已删除，无法生成画像分析",
                code="RESUME_PROFILE_NOT_FOUND",
            ) from exc
        # 八股知识与面经：按面试方向过滤；向量库异常时不阻断主流程，降级为空结果。
        knowledge_ctx = retrieve_knowledge(
            self._knowledge_query(role_direction),
            role_direction=role_direction,
            top_k=self.settings.profile_analyze_knowledge_top_k,
            settings=self.settings,
        )
        experience_ctx = retrieve_experience(
            self._knowledge_query(role_direction),
            role_direction=role_direction,
            top_k=self.settings.profile_analyze_knowledge_top_k,
            settings=self.settings,
        )

        analysis = await self._generate(
            profile=profile,
            role_direction=role_direction,
            resume_ctx=resume_ctx,
            knowledge_ctx=knowledge_ctx,
            experience_ctx=experience_ctx,
        )
        log.info(
            "画像分析生成完成 profile_id=%s version=%s direction=%s topics=%s",
            resume_profile_id,
            profile_version,
            role_direction,
            len(analysis.suggestedTopics),
        )
        return analysis

    def _load_confirmed_profile(
        self,
        resume_profile_id: int,
        profile_version: int,
    ) -> Mapping[str, Any]:
        """只读取已确认且未删除的画像，并校验版本未过期。"""
        with self.engine.connect() as connection:
            row = connection.execute(
                text(
                    """
                    SELECT id, user_id, resume_file_id, version, confirm_status, deleted,
                           candidate_name, raw_text, project_experience_json, skills_json
                    FROM resume_profile
                    WHERE id = :profile_id
                      AND deleted = 0
                    LIMIT 1
                    """
                ),
                {"profile_id": int(resume_profile_id)},
            ).mappings().first()

        if row is None:
            raise AppError(
                "简历画像不存在或已删除",
                code="RESUME_PROFILE_NOT_FOUND",
            )
        if row["confirm_status"] != "CONFIRMED":
            raise AppError(
                "简历画像尚未确认，不能生成画像分析",
                code="RESUME_PROFILE_NOT_CONFIRMED",
            )
        if int(row["version"]) != int(profile_version):
            raise AppError(
                "简历画像版本已更新，当前任务已失效",
                code="RESUME_PROFILE_VERSION_STALE",
            )
        with self.engine.connect() as connection:
            newer_confirmed = connection.execute(
                text(
                    """
                    SELECT 1
                    FROM resume_profile newer_profile
                    WHERE newer_profile.resume_file_id = :resume_file_id
                      AND newer_profile.deleted = 0
                      AND newer_profile.confirm_status = 'CONFIRMED'
                      AND (
                          newer_profile.version > :profile_version
                          OR (
                              newer_profile.version = :profile_version
                              AND newer_profile.id > :profile_id
                          )
                      )
                    LIMIT 1
                    """
                ),
                {
                    "resume_file_id": int(row["resume_file_id"]),
                    "profile_version": int(row["version"]),
                    "profile_id": int(row["id"]),
                },
            ).first()
        if newer_confirmed is not None:
            raise AppError(
                "简历画像版本已更新，当前任务已失效",
                code="RESUME_PROFILE_VERSION_STALE",
            )
        return row

    async def _generate(
        self,
        *,
        profile: Mapping[str, Any],
        role_direction: str,
        resume_ctx: dict[str, Any],
        knowledge_ctx: dict[str, Any],
        experience_ctx: dict[str, Any],
    ) -> ProfileAnalysis:
        """调用 DeepSeek JSON 模式生成画像分析，校验失败时自动修复一次。"""
        messages = _build_llm_messages(
            profile=profile,
            role_direction=role_direction,
            resume_ctx=resume_ctx,
            knowledge_ctx=knowledge_ctx,
            experience_ctx=experience_ctx,
        )
        try:
            payload = await _call_deepseek_json(messages, self.settings)
        except AppError as first_error:
            if first_error.code != "LLM_INVALID_JSON":
                raise
            # JSON 模式偶尔返回空内容或截断结果；仅针对这类可恢复错误重试一次。
            payload = await _call_deepseek_json(
                messages, self.settings, repair_error=first_error.message
            )
        try:
            analysis = ProfileAnalysis.model_validate(payload)
        except ValidationError as first_error:
            # 只做一次修复调用，避免模型异常时无限重试并放大成本。
            repaired_payload = await _call_deepseek_json(
                messages, self.settings, repair_error=str(first_error)
            )
            try:
                analysis = ProfileAnalysis.model_validate(repaired_payload)
            except ValidationError as second_error:
                raise AppError(
                    "模型返回的画像分析字段无法校验，请稍后重试",
                    code="LLM_SCHEMA_INVALID",
                    status_code=502,
                ) from second_error

        # 回填生成模型信息，供 profile_analysis 表记录审计。
        analysis.modelName = self.settings.deepseek_model
        analysis.modelVersion = "1.0.0"
        return analysis

    @staticmethod
    def _resume_query(role_direction: str) -> str:
        """简历向量检索的查询文本，贴合目标面试方向的语义。"""
        if role_direction == "AGENT_DEVELOPMENT":
            return "Agent 开发相关项目经验、技术栈与核心职责"
        return "Java 后端相关项目经验、技术栈与核心职责"

    @staticmethod
    def _knowledge_query(role_direction: str) -> str:
        """知识库/面经检索的查询文本。"""
        if role_direction == "AGENT_DEVELOPMENT":
            return "Agent 开发面试高频题、LangGraph、RAG、多智能体、工具调用"
        return "Java 后端面试高频题、并发、JVM、Spring、分布式、数据库"


def _build_llm_messages(
    *,
    profile: Mapping[str, Any],
    role_direction: str,
    resume_ctx: dict[str, Any],
    knowledge_ctx: dict[str, Any],
    experience_ctx: dict[str, Any],
) -> list[dict[str, str]]:
    """构造画像分析提示词，固定输出字段并给出可选的修复信息。"""
    system = (
        "你是资深的技术面试官与简历分析师。请基于候选人简历、简历向量片段、"
        "八股知识与面经案例，为指定面试方向生成一份画像分析。"
        "只输出 JSON 对象，不要输出 Markdown 或解释。字段必须严格包含："
        "skillTags、projectGraph、capabilityHints、riskPoints、suggestedTopics、stageTargets。"
        "字段格式："
        "skillTags 是数组，元素含 skill(技能名)、level(EXPERT|PROFICIENT|FAMILIAR|BASIC)、"
        "source(WORK|PROJECT|EDUCATION)；"
        "projectGraph 是对象，含 projects 数组，元素含 projectName、techStack、responsibilities、highlights；"
        "capabilityHints 是对象，含 engineering、architecture、domain 三个字符串数组；"
        "riskPoints 是数组，元素含 category(VAGUE_DESCRIPTION|SHALLOW_DEPTH|OUTDATED_TECH|LACK_EVIDENCE)、description；"
        "suggestedTopics 是字符串数组；"
        "stageTargets 是对象，含 basic、project、scenario 三个字符串数组。"
        "要求："
        "skillTags 只列与面试方向相关的核心技术并给出可信的等级推断；"
        "riskPoints 基于简历原文中的真实缺口（如项目描述空泛、技术深度不足、缺少量化成果）；"
        "suggestedTopics 结合知识库与面经给出面试官实际可提问的主题；"
        "stageTargets 分别给出八股、项目追问、场景题三个阶段的覆盖重点；"
        "全部使用中文，贴合候选人实际简历，不要编造简历中没有的技术背景。"
    )
    user = (
        f"面试方向：{role_direction}\n"
        f"候选人姓名：{profile.get('candidate_name') or ''}\n"
        f"候选人技能：{_render(profile.get('skills_json'))}\n"
        f"候选人项目经历：{_render(profile.get('project_experience_json'))}\n"
        f"简历原文片段：{_render(profile.get('raw_text'))[:8000]}\n"
        f"简历向量检索片段：\n{_render_chunks(resume_ctx)}\n"
        f"八股知识检索结果：\n{_render_chunks(knowledge_ctx)}\n"
        f"面经案例检索结果：\n{_render_chunks(experience_ctx)}"
    )
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def _render_chunks(ctx: Mapping[str, Any]) -> str:
    """把检索结果展平为可读文本；空结果或降级时给出明确标记。"""
    chunks = ctx.get("chunks") or []
    if not chunks:
        return "（无可用检索结果）"
    lines = []
    for chunk in chunks[:10]:
        content = chunk.get("content")
        if content:
            lines.append(f"- {str(content)[:500]}")
    return "\n".join(lines) if lines else "（无可用检索结果）"


def _render(value: Any) -> str:
    """将简历 JSON 字段渲染为可读文本。"""
    if value is None:
        return ""
    if isinstance(value, str):
        return value.strip()
    try:
        return json.dumps(value, ensure_ascii=False)
    except (TypeError, ValueError):
        return str(value)


def _parse_json_content(content: Any) -> dict[str, Any]:
    """兼容模型偶尔包裹 ```json 代码围栏的情况，同时禁止非对象结果。"""
    if not isinstance(content, str) or not content.strip():
        raise AppError(
            "模型返回的画像分析 JSON 为空或格式无效",
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
            raise AppError(
                "模型返回的画像分析 JSON 格式无效",
                code="LLM_INVALID_JSON",
                status_code=502,
            )
        try:
            value = json.loads(cleaned[start : end + 1])
        except json.JSONDecodeError as exc:
            raise AppError(
                "模型返回的画像分析 JSON 格式无效",
                code="LLM_INVALID_JSON",
                status_code=502,
            ) from exc
    if not isinstance(value, dict):
        raise AppError(
            "模型返回的画像分析结果不是 JSON 对象",
            code="LLM_INVALID_JSON",
            status_code=502,
        )
    return value


async def _call_deepseek_json(
    messages: list[dict[str, str]],
    settings: Settings,
    repair_error: str | None = None,
) -> dict[str, Any]:
    """调用 DeepSeek JSON 模式；API Key 缺失时给出明确配置错误。"""
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
    log.info("调用 DeepSeek 画像分析 repair=%s", bool(repair_error))
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
            parsed = _parse_json_content(content)
            log.info("DeepSeek 画像分析调用成功")
            return parsed
    except AppError:
        raise
    except (httpx.HTTPError, KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        log.exception("DeepSeek 画像分析失败")
        raise AppError(
            "画像分析服务暂时不可用，请稍后重试",
            code="LLM_REQUEST_FAILED",
            status_code=502,
        ) from exc


async def analyze_profile(
    resume_profile_id: str,
    profile_version: int,
    role_direction: str,
    *,
    settings: Settings | None = None,
) -> ProfileAnalysis:
    """Worker 与 HTTP 端点共用的便捷入口。"""
    return await ProfileAnalyzer(settings).analyze(
        resume_profile_id,
        profile_version,
        role_direction,
    )


def resolve_latest_confirmed_profile(
    resume_profile_id: str,
    *,
    settings: Settings | None = None,
    engine: Engine | Any | None = None,
) -> tuple[int, int]:
    """解析指定画像所在简历文件当前最新已确认画像的 (profile_id, version)。

    HTTP 端点契约未携带 profileVersion，按"分析该简历文件最新已确认版本"语义解析：
    先取该画像的 resume_file_id，再取同文件 confirm_status=CONFIRMED 的最大版本行。
    这样调用方即使传入过期的 profile_id，也能自动解析到新版本。
    """
    runtime_settings = settings or get_settings()
    analyzer = ProfileAnalyzer(runtime_settings, engine=engine)
    with analyzer.engine.connect() as connection:
        row = connection.execute(
            text(
                """
                SELECT id, version
                FROM resume_profile
                WHERE resume_file_id = (
                        SELECT resume_file_id
                        FROM resume_profile
                        WHERE id = :profile_id
                          AND deleted = 0
                        LIMIT 1
                )
                  AND confirm_status = 'CONFIRMED'
                  AND deleted = 0
                ORDER BY version DESC, id DESC
                LIMIT 1
                """
            ),
            {"profile_id": int(resume_profile_id)},
        ).mappings().first()
    if row is None:
        raise AppError(
            "简历画像不存在、未确认或已删除",
            code="RESUME_PROFILE_NOT_FOUND",
        )
    return int(row["id"]), int(row["version"])


async def analyze_profile_latest(
    resume_profile_id: str,
    role_direction: str,
    *,
    settings: Settings | None = None,
) -> ProfileAnalysis:
    """HTTP 端点使用的便捷入口：自动解析最新已确认画像后再分析。"""
    latest_id, latest_version = resolve_latest_confirmed_profile(
        resume_profile_id, settings=settings
    )
    return await analyze_profile(
        str(latest_id),
        latest_version,
        role_direction,
        settings=settings,
    )
