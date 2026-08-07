"""面试首题生成 LangGraph 状态机。

职责边界（docs/interview-policy.md 1.x）：
- FastAPI 只做 AI 能力：读已确认简历、检索知识/面经/简历切片，并生成首题事实
  （题目正文、主题、来源、期望要点），不返回任何具有最终业务语义的决策字段；
- 阶段计划由 Spring 端确定性生成后透传，本图只消费其中 BASIC 阶段的必覆盖主题，
  用于出题上下文，不做计划调整。

图流程：resolve_basic_topics → load_profile → retrieve_contexts → generate_question。
节点均为纯状态函数，便于单测注入替身；LangGraph 提供可追踪的分步状态与中断/恢复基础。
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Mapping, TypedDict

from langgraph.graph import END, START, StateGraph
from sqlalchemy import Engine, text

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.retrievers.experience_retriever import retrieve_experience
from app.retrievers.knowledge_retriever import retrieve_knowledge
from app.retrievers.resume_retriever import retrieve_resume_context
from app.schemas.interview import (
    CaseRef,
    GenerateFirstQuestionRequest,
    KnowledgeRef,
    QuestionResponse,
    SourceType,
)
from app.services.deepseek_client import call_deepseek_json
from app.services.resume_vectorizer import build_mysql_engine

log = logging.getLogger(__name__)

# 检索返回条数：复用画像分析阶段的既有配置，避免为面试重复加配置项
_KNOWLEDGE_TOP_K = 5
_EXPERIENCE_TOP_K = 3
_RESUME_TOP_K = 5

# 各阶段必覆盖主题条数上限，避免 query 文本过长
_MAX_TOPICS_IN_QUERY = 5


class FirstQuestionState(TypedDict, total=False):
    """首题生成图的状态（部分键：节点只更新自己负责的键）。"""

    session_id: str
    resume_profile_id: int
    profile_version: int
    role_direction: str
    stage_plan: dict[str, Any]
    basic_topics: list[str]
    profile: dict[str, Any] | None
    knowledge_ctx: dict[str, Any] | None
    experience_ctx: dict[str, Any] | None
    resume_ctx: dict[str, Any] | None
    result: dict[str, Any] | None


class FirstQuestionGraph:
    """首题生成状态机；依赖（引擎/检索器）可注入以便测试。"""

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        engine: Engine | Any | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.engine = engine or build_mysql_engine(self.settings)
        self._compiled = self._build()

    def _build(self) -> Any:
        """组装并编译 LangGraph 图。"""
        builder = StateGraph(FirstQuestionState)
        builder.add_node("resolve_basic_topics", self._resolve_basic_topics)
        builder.add_node("load_profile", self._load_profile)
        builder.add_node("retrieve_contexts", self._retrieve_contexts)
        builder.add_node("generate_question", self._generate_question)
        builder.add_edge(START, "resolve_basic_topics")
        builder.add_edge("resolve_basic_topics", "load_profile")
        builder.add_edge("load_profile", "retrieve_contexts")
        builder.add_edge("retrieve_contexts", "generate_question")
        builder.add_edge("generate_question", END)
        return builder.compile()

    async def generate(self, request: GenerateFirstQuestionRequest) -> QuestionResponse:
        """执行首题生成并返回契约响应。

        确定性业务错误（画像不存在/未确认/版本过期）与 LLM 可恢复错误
        统一映射为 success=false；未预期异常记日志并返回可读错误。
        """
        initial: FirstQuestionState = {
            "session_id": request.sessionId,
            "resume_profile_id": int(request.resumeProfileId),
            "profile_version": request.profileVersion,
            "role_direction": request.roleDirection,
            "stage_plan": request.stagePlan.model_dump(mode="json", exclude_none=True),
            "basic_topics": [],
            "profile": None,
            "knowledge_ctx": None,
            "experience_ctx": None,
            "resume_ctx": None,
            "result": None,
        }
        try:
            final = await self._compiled.ainvoke(initial)
            result = final.get("result")
            if not result:
                raise AppError("首题生成结果为空", code="EMPTY_QUESTION_RESULT")
            return QuestionResponse(success=True, **result)
        except AppError as exc:
            # 确定性业务错误直接返回，不隐藏可读原因
            return QuestionResponse(success=False, errorMessage=exc.message)
        except Exception as exc:  # noqa: BLE001 - 对外隐藏内部堆栈，保留统一可读错误
            log.exception(
                "首题生成图执行异常 session_id=%s profile_id=%s",
                request.sessionId,
                request.resumeProfileId,
            )
            return QuestionResponse(success=False, errorMessage=f"首题生成失败：{exc}")

    # ==================== 图节点 ====================

    def _resolve_basic_topics(self, state: FirstQuestionState) -> dict[str, Any]:
        """从阶段计划提取 BASIC 阶段必覆盖主题；缺失时回退方向默认主题。"""
        stages = (state.get("stage_plan") or {}).get("stages") or []
        basic = next(
            (stage for stage in stages if stage.get("stage") == "BASIC"), None
        )
        topics = (basic or {}).get("required_topics") or []
        topics = [str(t) for t in topics if str(t).strip()]
        if not topics:
            topics = self._default_basic_topics(state.get("role_direction", ""))
        log.info(
            "解析 BASIC 必覆盖主题 session_id=%s topics=%s",
            state.get("session_id"),
            topics[: _MAX_TOPICS_IN_QUERY],
        )
        return {"basic_topics": topics[:_MAX_TOPICS_IN_QUERY]}

    def _load_profile(self, state: FirstQuestionState) -> dict[str, Any]:
        """读取已确认且版本未过期的简历画像；不满足时抛确定性 AppError。"""
        profile = self._load_confirmed_profile(
            state["resume_profile_id"], state["profile_version"]
        )
        return {"profile": dict(profile)}

    async def _retrieve_contexts(self, state: FirstQuestionState) -> dict[str, Any]:
        """并行检索知识库、面经与简历切片，作为出题上下文。"""
        profile = state["profile"] or {}
        direction = state["role_direction"]
        topics = state.get("basic_topics") or []
        query = self._retrieval_query(direction, topics)

        knowledge, experience, resume = await asyncio.gather(
            asyncio.to_thread(
                retrieve_knowledge,
                query,
                role_direction=direction,
                top_k=self.settings.profile_analyze_knowledge_top_k,
                settings=self.settings,
            ),
            asyncio.to_thread(
                retrieve_experience,
                query,
                role_direction=direction,
                top_k=_EXPERIENCE_TOP_K,
                settings=self.settings,
            ),
            asyncio.to_thread(
                retrieve_resume_context,
                query,
                user_id=int(profile["user_id"]),
                resume_profile_id=int(profile["id"]),
                top_k=_RESUME_TOP_K,
                settings=self.settings,
            ),
            return_exceptions=True,
        )
        # 检索失败不阻断出题：降级为空上下文，LLM 仍可基于简历与计划出题
        knowledge = self._safe_ctx(knowledge)
        experience = self._safe_ctx(experience)
        resume = self._safe_ctx(resume)
        log.info(
            "首题检索完成 session_id=%s knowledge=%s experience=%s resume=%s",
            state.get("session_id"),
            len(knowledge.get("chunks") or []),
            len(experience.get("chunks") or []),
            len(resume.get("chunks") or []),
        )
        return {
            "knowledge_ctx": knowledge,
            "experience_ctx": experience,
            "resume_ctx": resume,
        }

    async def _generate_question(self, state: FirstQuestionState) -> dict[str, Any]:
        """调用 DeepSeek 生成首题事实，并回填真实的引用片段（溯源）。"""
        profile = state["profile"] or {}
        messages = self._build_llm_messages(state, profile)
        payload = await call_deepseek_json(
            messages, self.settings, what="首题", repair_error=None
        )
        source_type = self._normalize_source_type(payload.get("sourceType"))
        result = {
            "questionText": str(payload.get("questionText") or "").strip(),
            "topic": str(payload.get("topic") or "").strip(),
            "questionType": "OPENING",
            "sourceType": source_type,
            "expectedPoints": [
                str(point) for point in (payload.get("expectedPoints") or []) if str(point).strip()
            ],
            "knowledgeRefs": self._to_knowledge_refs(state.get("knowledge_ctx")),
            "caseRefs": self._to_case_refs(state.get("experience_ctx")),
        }
        if not result["questionText"] or not result["topic"]:
            raise AppError(
                "模型返回的首题缺少正文或主题",
                code="LLM_INVALID_JSON",
                status_code=502,
            )
        log.info(
            "首题生成完成 session_id=%s topic=%s source=%s",
            state.get("session_id"),
            result["topic"],
            source_type,
        )
        return {"result": result}

    # ==================== 私有辅助 ====================

    def _load_confirmed_profile(
        self, resume_profile_id: int, profile_version: int
    ) -> Mapping[str, Any]:
        """只读取已确认且未删除的画像，并校验版本未过期。

        与画像分析读取逻辑保持一致：Spring 侧已做画像确认校验，此处再校验
        一次防止直接调用 HTTP 接口绕过业务前置条件。
        """
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
                {"profile_id": resume_profile_id},
            ).mappings().first()

        if row is None:
            raise AppError(
                "简历画像不存在或已删除，无法生成首题",
                code="RESUME_PROFILE_NOT_FOUND",
            )
        if row["confirm_status"] != "CONFIRMED":
            raise AppError(
                "简历画像尚未确认，无法生成首题",
                code="RESUME_PROFILE_NOT_CONFIRMED",
            )
        if int(row["version"]) != profile_version:
            raise AppError(
                "简历画像版本已更新，当前请求已失效",
                code="RESUME_PROFILE_VERSION_STALE",
            )
        return row

    def _retrieval_query(self, role_direction: str, topics: list[str]) -> str:
        """组合检索查询文本：方向 + BASIC 必覆盖主题，贴合首题语义。"""
        direction_text = (
            "Agent 开发"
            if role_direction == "AGENT_DEVELOPMENT"
            else "Java 后端"
        )
        if topics:
            return f"{direction_text}面试基础题：{'、'.join(topics)}"
        return f"{direction_text}面试高频基础题、核心概念与原理"

    @staticmethod
    def _default_basic_topics(role_direction: str) -> list[str]:
        """BASIC 必覆盖主题缺失时的方向默认主题（与 Spring StagePlanBuilder 兜底一致）。"""
        if role_direction == "AGENT_DEVELOPMENT":
            return ["LangGraph 状态机", "RAG 检索增强", "Agent 工具调用"]
        return ["Java 并发", "JVM", "Spring 框架"]

    def _build_llm_messages(
        self, state: FirstQuestionState, profile: Mapping[str, Any]
    ) -> list[dict[str, str]]:
        """构造首题生成提示词：固定输出字段，给出检索材料。"""
        system = (
            "你是资深的技术面试官。请基于候选人简历、八股知识与面经案例，"
            "为指定面试方向生成面试的第一道题（基础八股阶段的开场题）。"
            "第一道题应是该方向的高频基础题，贴合必覆盖主题，并尽可能结合候选人的"
            "简历技能做个性化。只输出 JSON 对象，不要输出 Markdown 或解释。"
            "字段必须严格包含："
            "questionText(题目正文)、topic(题目主题)、"
            "sourceType(来源类型：KNOWLEDGE_BASE|EXPERIENCE_CASE|RESUME_PROJECT|MIXED)、"
            "expectedPoints(期望回答要点数组)。"
            "要求：题目要具体、可作答，不要空洞；不要编造简历中没有的技术背景；"
            "全部使用中文。"
        )
        user = (
            f"面试方向：{state['role_direction']}\n"
            f"基础八股阶段必覆盖主题：{'、'.join(state.get('basic_topics') or [])}\n"
            f"候选人姓名：{profile.get('candidate_name') or ''}\n"
            f"候选人技能：{_render(profile.get('skills_json'))}\n"
            f"候选人项目经历：{_render(profile.get('project_experience_json'))}\n"
            f"简历原文片段：{_render(profile.get('raw_text'))[:4000]}\n"
            f"八股知识检索结果：\n{_render_chunks(state.get('knowledge_ctx'))}\n"
            f"面经案例检索结果：\n{_render_chunks(state.get('experience_ctx'))}\n"
            f"简历向量检索片段：\n{_render_chunks(state.get('resume_ctx'))}"
        )
        return [{"role": "system", "content": system}, {"role": "user", "content": user}]

    @staticmethod
    def _normalize_source_type(raw: Any) -> SourceType:
        """来源类型合法性归一：非法值回退为知识库，保证溯源字段可枚举。"""
        if raw in ("KNOWLEDGE_BASE", "EXPERIENCE_CASE", "RESUME_PROJECT", "MIXED"):
            return raw
        return "KNOWLEDGE_BASE"

    @staticmethod
    def _to_knowledge_refs(ctx: dict[str, Any] | None) -> list[KnowledgeRef]:
        """从知识检索结果构建溯源引用（真实命中片段，避免模型幻觉）。"""
        chunks = (ctx or {}).get("chunks") or []
        refs: list[KnowledgeRef] = []
        for chunk in chunks[:_KNOWLEDGE_TOP_K]:
            content = str(chunk.get("content") or "")
            if not content.strip():
                continue
            metadata = chunk.get("metadata") or {}
            refs.append(
                KnowledgeRef(
                    title=metadata.get("title")
                    or metadata.get("category")
                    or content.strip()[:40],
                    category=metadata.get("category"),
                    snippet=content.strip()[:200],
                )
            )
        return refs

    @staticmethod
    def _to_case_refs(ctx: dict[str, Any] | None) -> list[CaseRef]:
        """从面经检索结果构建溯源引用。"""
        chunks = (ctx or {}).get("chunks") or []
        refs: list[CaseRef] = []
        for chunk in chunks[:_EXPERIENCE_TOP_K]:
            content = str(chunk.get("content") or "")
            if not content.strip():
                continue
            metadata = chunk.get("metadata") or {}
            refs.append(
                CaseRef(
                    title=metadata.get("title") or content.strip()[:40],
                    scenario=metadata.get("scenario")
                    or metadata.get("category"),
                    snippet=content.strip()[:200],
                )
            )
        return refs

    @staticmethod
    def _safe_ctx(value: Any) -> dict[str, Any]:
        """检索异常/缺省时返回空上下文，避免下游空指针。"""
        if isinstance(value, dict) and isinstance(value.get("chunks"), list):
            return value
        return {"chunks": []}


def _render_chunks(ctx: Mapping[str, Any] | None) -> str:
    """把检索结果展平为可读文本；空结果时给出明确标记。"""
    chunks = (ctx or {}).get("chunks") or []
    if not chunks:
        return "（无可用检索结果）"
    lines = []
    for chunk in chunks[:8]:
        content = str(chunk.get("content") or "")
        if content.strip():
            lines.append(f"- {content.strip()[:400]}")
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
