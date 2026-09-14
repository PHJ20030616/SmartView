"""报告生成服务：读 MySQL 聚合会话/问答/评估/画像数据，混合式生成面试复盘报告。

混合式生成（设计决策）：
- 量化指标（综合得分/准备度/岗位匹配度/覆盖率）由 ReportScorer 确定性计算，
  不依赖 LLM，可单测、可复现；
- 定性内容（总体评价/优势/薄弱/风险/建议）与每题参考答案由 LLM 结构化生成，
  输出经 schema 校验，失败时做一次带上下文的修复调用（JSON 解析失败与字段校验
  失败都会触发，见 _is_repairable）。

ReportScorer 作为独立类保留，后续可平移到独立的"得分评测"Agent。
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Mapping

from sqlalchemy import Engine, text

from app.core.config import Settings, get_settings
from app.core.errors import AppError
from app.schemas.report import (
    ReportCoverage,
    ReportGenerateResult,
    ReportReferenceAnswer,
)
from app.services.deepseek_client import call_deepseek_json
from app.services.resume_vectorizer import build_mysql_engine

log = logging.getLogger(__name__)

# 参考答案类型按题目阶段确定性映射（design §7）。
ANSWER_TYPE_BY_STAGE = {
    "BASIC": "BASIC_KEY_POINTS",
    "PROJECT": "PROJECT_STRUCTURE",
    "SCENARIO": "SCENARIO_FRAMEWORK",
}

# 单题参考答案的输出上限。
#
# 历史实现用一次调用为**全部**已答题生成参考答案，输出随题量线性增长，最后必然撞上
# max_tokens 被截断——而截断后的 JSON 必然解析失败，表现为"模型返回的参考答案 JSON
# 格式无效"。生产数据可证：同一个提示词被发 8 次、7 次失败，失败耗时 34~41 秒
# （模型把上限 token 全吐完才被截断）；把上限从 8192 抬到 16384 后输出 15417，
# 仍占上限 94%，只是把爆点推后一道题而已。
#
# 改成每道题一次调用后，单次输出量与题量解耦：4096 对单题是宽松上限
# （约合 3000 中文字，远超一份参考答案的实际长度），同时把"线性增长撞上限"
# 这个结构性矛盾从根上消除。
_REFERENCE_ANSWER_MAX_TOKENS_PER_QUESTION = 4096


def _is_repairable(exc: Exception) -> bool:
    """判断一次失败是否值得再发一次带修复上下文的调用。

    - ValueError：JSON 合法但字段不满足 schema，补上具体错误说明再问一次通常能修好；
    - AppError(LLM_INVALID_JSON)：模型这次没吐出合法 JSON（长输出被截断是最常见原因），
      追加修复指令后重发有机会拿到完整输出；
    - AppError(LLM_OUTPUT_TRUNCATED)：输出顶到 max_tokens 被截断。重发同一份提示词与
      同一个上限只会再次截断，但补上"上次输出被截断，请精简"的修复指令后仍有救，
      因此也归入可修复；真正的解决方案是缩小单次请求规模（见分批生成）。

    其它 AppError（未配置密钥、请求被拒、传输失败等）重发同一份提示词不会改变结果，
    应由 worker 的有界重试处理，这里不做无效修复调用。
    """
    if isinstance(exc, ValueError):
        return True
    return isinstance(exc, AppError) and exc.code in {
        "LLM_INVALID_JSON",
        "LLM_OUTPUT_TRUNCATED",
    }


class ReportScorer:
    """确定性评分：从逐题评估得分与覆盖度计算报告量化指标。

    不调用 LLM；输入为聚合后的评估行与解析后的阶段计划/覆盖度 JSON。
    """

    # 阶段代码 → 覆盖率字段名
    STAGE_COVERAGE_FIELDS = {
        "BASIC": "basicCoverage",
        "PROJECT": "projectCoverage",
        "SCENARIO": "scenarioCoverage",
    }

    def __init__(
        self,
        evaluations: list[Mapping[str, Any]],
        stage_plan: dict[str, Any],
        stage_coverage: dict[str, Any],
    ) -> None:
        # evaluations: [{question_id, order, stage, score}]，按 question_order 升序
        self.evaluations = evaluations
        self.stage_plan = stage_plan or {}
        self.stage_coverage = stage_coverage or {}

    def _weighted_mean(self, rows: list[Mapping[str, Any]]) -> int | None:
        """加权均值：权重 w = 1 + 0.2×order，越靠后的题权重略高（反映当前水平）。"""
        scored = [r for r in rows if r.get("score") is not None]
        if not scored:
            return None
        total_weight = sum(1.0 + 0.2 * int(r["order"]) for r in scored)
        total_score = sum(
            int(r["score"]) * (1.0 + 0.2 * int(r["order"])) for r in scored
        )
        return int(round(total_score / total_weight))

    def overall_score(self) -> int | None:
        """综合得分：全部已答题评估得分的加权均值。"""
        return self._weighted_mean(self.evaluations)

    def readiness_level(self) -> str:
        """面试准备度：由综合得分阈值映射。"""
        score = self.overall_score()
        if score is None:
            return "NOT_READY"
        if score < 40:
            return "NOT_READY"
        if score < 60:
            return "NEEDS_PRACTICE"
        if score < 80:
            return "READY"
        return "WELL_PREPARED"

    def role_fit_score(self) -> int | None:
        """岗位匹配度：PROJECT/SCENARIO 阶段得分的加权均值；无该阶段题时退化为综合得分。"""
        role_rows = [
            r for r in self.evaluations if r.get("stage") in ("PROJECT", "SCENARIO")
        ]
        if not role_rows:
            return self.overall_score()
        return self._weighted_mean(role_rows)

    def coverage(self) -> dict[str, float]:
        """三阶段覆盖率：covered_topics ∩ required_topics / required_topics。"""
        plan_stages = {
            s.get("stage"): s for s in (self.stage_plan.get("stages") or [])
        }
        result: dict[str, float] = {}
        for stage, field in self.STAGE_COVERAGE_FIELDS.items():
            required = [
                t
                for t in (plan_stages.get(stage, {}).get("required_topics") or [])
                if t
            ]
            covered = [
                t
                for t in (self.stage_coverage.get(stage, {}).get("covered_topics") or [])
                if t
            ]
            if not required:
                # 无必覆盖主题视为全覆盖，避免除零。
                result[field] = 1.0
                continue
            covered_set = set(covered)
            ratio = sum(1 for t in required if t in covered_set) / len(required)
            result[field] = round(ratio, 2)
        return result


class ReportNarrativeGenerator:
    """基于画像与评估事实生成报告定性内容（LLM，结构化输出）。"""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    async def generate(self, context: dict[str, Any]) -> dict[str, Any]:
        prompt = self._build_prompt(context)
        try:
            raw = await call_deepseek_json(
                prompt,
                self.settings,
                scene="report_generate",
                what="报告评语",
                prompt_key="report_generate.narrative",
            )
            return self._validate(raw)
        except (AppError, ValueError) as exc:
            # 两类失败都做一次带上下文的修复调用：模型返回的 JSON 解析失败
            # （_is_repairable 覆盖）与 JSON 合法但字段不满足 schema。
            if not _is_repairable(exc):
                raise
            repaired = await call_deepseek_json(
                prompt,
                self.settings,
                scene="report_generate",
                what="报告评语",
                repair_error=str(exc),
                prompt_key="report_generate.narrative",
            )
            try:
                return self._validate(repaired)
            except ValueError as exc2:
                # 修复调用后仍校验失败 → 抛 AppError 作为确定性终态：Task 10 worker
                # 按 AppError.code 识别，不再重试（区别于未预期异常走"有界重试"）。
                raise AppError(
                    "AI 生成的报告评语校验失败",
                    code="REPORT_LLM_VALIDATION_FAILED",
                ) from exc2

    def _validate(self, raw: dict[str, Any]) -> dict[str, Any]:
        for field in ("summary", "strengths", "weaknesses", "riskPoints", "suggestions"):
            if not raw.get(field):
                raise ValueError(f"报告评语缺少字段 {field}")
        # 数组字段统一做 isinstance 校验：非 list 的"真值"（如字符串/对象）能绕过
        # 上面的空值检查，若不拦截会在构造 ReportGenerateResult 时抛未捕获的
        # Pydantic ValidationError（strengths/weaknesses/riskPoints 为 string[]，
        # suggestions 为 object[]，均必须是 list）。
        for field in ("strengths", "weaknesses", "riskPoints", "suggestions"):
            if not isinstance(raw[field], list):
                raise ValueError(f"报告评语 {field} 必须为数组")
        return raw

    @staticmethod
    def _build_prompt(context: dict[str, Any]) -> list[dict[str, str]]:
        return [
            {
                "role": "system",
                "content": (
                    "你是资深技术面试官，根据候选人的面试表现与简历画像，"
                    "输出中文面试复盘报告的定性内容。"
                    "只输出 JSON 对象，字段：summary(string 总体评价)、"
                    "strengths(string[] 优势点)、weaknesses(string[] 薄弱点)、"
                    "riskPoints(string[] 风险点)、"
                    "suggestions(object[] {topic,reason,resources:string[]} 学习建议)。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps(context, ensure_ascii=False),
            },
        ]


class ReferenceAnswerGenerator:
    """为每道已回答问题生成参考答案（LLM，逐题一次结构化调用，题间并发）。

    answerType 按题目阶段确定性映射，LLM 仅产出 referenceContent/keyPoints/tradeoffs，
    保证参考答案类型与题目阶段严格一致。

    逐题生成而不是一次生成全部：前者让单次输出规模与题量解耦（见
    _REFERENCE_ANSWER_MAX_TOKENS_PER_QUESTION 的说明），单题失败只需重试该题，
    不再把已经生成的其它题的成果一起作废。
    """

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()

    async def generate(
        self,
        questions: list[dict[str, Any]],
        stage_by_question: Mapping[str, str],
    ) -> list[dict[str, Any]]:
        """questions: [{question_id, question_text, topic, stage, expected_points,
        actual_answer, score, matched_points, missing_points}]。

        返回顺序与入参 questions 一致（asyncio.gather 保序），便于落库与前端逐题展示。
        """
        if not questions:
            return []
        # 题间彼此独立（提示词只用到当前题的信息），因此用信号量限制并发度后一起发出：
        # 串行 N 题会让报告生成总耗时随题量线性增长，并发后总耗时回落到单题量级。
        # 并发度取配置值（LLM_MAX_CONCURRENCY），网关限流时调成 1 即退回串行。
        concurrency = max(1, int(self.settings.llm_max_concurrency))
        semaphore = asyncio.Semaphore(concurrency)

        async def _generate_with_limit(question: dict[str, Any]) -> dict[str, Any]:
            async with semaphore:
                return await self._generate_one(question, stage_by_question)

        results = await asyncio.gather(
            *(_generate_with_limit(question) for question in questions),
            return_exceptions=True,
        )

        # 一张报告要么每道题都有参考答案，要么整体失败（验收标准：
        # 每道 ANSWERED 题必须有参考答案）。因此先把成功的题收齐，
        # 再抛出第一个异常——半份参考答案不能让前端展示成"生成完成"。
        items: list[dict[str, Any]] = []
        first_error: BaseException | None = None
        for result in results:
            if isinstance(result, BaseException):
                first_error = first_error or result
                continue
            items.append(result)
        if first_error is not None:
            raise first_error

        # 不变量守卫：逐题生成后每道题各自对应一条结果，理论上不会重复或缺失。
        # 仍然显式校验一次——它是"落库参考答案与已答题一一对应"的最后一道闸门，
        # 代价是一次集合比较，收益是失败时立刻暴露而不是把脏数据写进报告。
        returned_ids = [item["questionId"] for item in items]
        if len(returned_ids) != len(set(returned_ids)):
            raise AppError(
                "AI 生成的参考答案存在重复题目",
                code="REPORT_REFERENCE_VALIDATION_FAILED",
            )
        missing_ids = sorted(set(stage_by_question) - set(returned_ids))
        if missing_ids:
            raise AppError(
                f"AI 生成的参考答案未覆盖全部已答题，缺少: {missing_ids}",
                code="REPORT_REFERENCE_VALIDATION_FAILED",
            )
        return items

    async def _generate_one(
        self,
        question: dict[str, Any],
        stage_by_question: Mapping[str, str],
    ) -> dict[str, Any]:
        """生成单题参考答案；可修复的失败补一次带上下文的修复调用。"""
        prompt = self._build_prompt([question])
        question_id = str(question["question_id"])
        try:
            raw = await call_deepseek_json(
                prompt,
                self.settings,
                scene="report_generate",
                what="参考答案",
                max_tokens=_REFERENCE_ANSWER_MAX_TOKENS_PER_QUESTION,
                prompt_key="report_generate.reference_answer",
            )
            return self._validate_one(raw, question, stage_by_question)
        except (AppError, ValueError) as exc:
            # 与报告评语一致：JSON 解析失败（含输出被截断）与 schema 校验失败都修复一次。
            # 修复调用沿用同一份 prompt（request_hash 相同、retry_attempt=1），
            # 因此"修复率"仍可统计。
            if not _is_repairable(exc):
                raise
            repaired = await call_deepseek_json(
                prompt,
                self.settings,
                scene="report_generate",
                what="参考答案",
                repair_error=str(exc),
                max_tokens=_REFERENCE_ANSWER_MAX_TOKENS_PER_QUESTION,
                prompt_key="report_generate.reference_answer",
            )
            try:
                return self._validate_one(repaired, question, stage_by_question)
            except ValueError as exc2:
                # 修复调用后仍校验失败 → 抛 AppError 作为确定性终态：Task 10 worker
                # 按 AppError.code 识别，不再重试（与 ReportNarrativeGenerator 保持一致）。
                # 带上题目标识：逐题生成后错误信息必须能定位到具体是哪道题。
                raise AppError(
                    f"AI 生成的参考答案校验失败（题目 {question_id}）",
                    code="REPORT_REFERENCE_VALIDATION_FAILED",
                ) from exc2

    def _validate_one(
        self,
        raw: dict[str, Any],
        question: dict[str, Any],
        stage_by_question: Mapping[str, str],
    ) -> dict[str, Any]:
        """校验单题参考答案输出，并归一化 answerType。

        模型被要求返回 {referenceAnswers: [...]} 结构；这里只接受恰好包含
        本次请求的那一道题，多返回或返回别的题目一律判为无效——否则并发场景下
        某次调用的结果会覆盖另一道题的数据。
        """
        items = raw.get("referenceAnswers")
        if not isinstance(items, list) or not items:
            raise ValueError("参考答案输出缺少 referenceAnswers 数组")
        question_id = str(question["question_id"])
        matched = [
            item
            for item in items
            if isinstance(item, dict) and str(item.get("questionId") or "").strip() == question_id
        ]
        if not matched:
            # 模型返回的 questionId 与请求不一致（改名、漏字段或返回了别题）
            raise ValueError(f"参考答案缺少本次请求的题目: {question_id}")
        item = matched[0]
        content = str(item.get("referenceContent") or "").strip()
        if not content:
            raise ValueError(f"参考答案缺少 referenceContent: {question_id}")
        # answerType 以题目阶段为准，确定性覆盖 LLM 返回值，避免类型与题目不符。
        stage = stage_by_question.get(question_id, "BASIC")
        answer_type = ANSWER_TYPE_BY_STAGE.get(stage, "BASIC_KEY_POINTS")
        return {
            "questionId": question_id,
            "answerType": answer_type,
            "referenceContent": content,
            "keyPoints": item.get("keyPoints") or [],
            "tradeoffs": item.get("tradeoffs") or [],
        }

    @staticmethod
    def _build_prompt(questions: list[dict[str, Any]]) -> list[dict[str, str]]:
        return [
            {
                "role": "system",
                "content": (
                    "你是资深技术面试官，为每道面试题生成一份参考答（复盘用）。"
                    "按题目阶段决定内容侧重："
                    "BASIC 基础题→给出关键知识点与要点；"
                    "PROJECT 项目题→给出优秀回答结构（STAR 组织+亮点表达）；"
                    "SCENARIO 场景题→给出分析框架、方案取舍与落地方案（含 tradeoffs）。"
                    "结合候选人的实际回答与评估缺失点，使参考答有针对性。"
                    "只输出 JSON 对象：referenceAnswers 数组，每项含 "
                    "questionId(string)、referenceContent(string 参考答案正文)、"
                    "keyPoints(string[] 关键要点)、tradeoffs(object[] {aspect,options:string[]} 权衡点)。"
                    "不要输出 answerType 字段（由系统按题目阶段确定）。"
                ),
            },
            {
                "role": "user",
                "content": json.dumps(questions, ensure_ascii=False),
            },
        ]


class ReportGenerator:
    """报告生成编排：读 MySQL 聚合数据 → 确定性评分 → LLM 定性内容与参考答案。"""

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        engine: Engine | Any | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.engine = engine or build_mysql_engine(self.settings)

    async def generate(self, session_id: str) -> ReportGenerateResult:
        """生成指定会话的报告结果（不含 MQ 信封字段，由 worker 组装）。"""
        session = self._load_session(session_id)
        report_id = self._load_report_id(session_id)
        questions = self._load_answered_questions(session_id)
        if not questions:
            raise AppError("会话没有已回答问题，无法生成报告", code="NO_ANSWERED_QUESTION")
        answers = self._load_answers(session_id)
        evaluations = self._load_evaluations(session_id)
        profile = self._load_profile(session["resume_profile_id"])
        analysis = self._load_analysis(session["profile_analysis_id"])

        # 聚合逐题评估事实
        eval_by_question: dict[str, dict[str, Any]] = {
            str(e["question_id"]): e for e in evaluations
        }
        answer_by_question: dict[str, str] = {
            str(a["question_id"]): str(a["answer_text"] or "") for a in answers
        }
        scored_rows: list[dict[str, Any]] = []
        question_refs: list[dict[str, Any]] = []
        stage_by_question: dict[str, str] = {}
        for q in questions:
            qid = str(q["id"])
            stage = q.get("stage") or "BASIC"
            stage_by_question[qid] = stage
            evaluation = eval_by_question.get(qid, {})
            scored_rows.append(
                {
                    "question_id": qid,
                    "order": int(q["question_order"]),
                    "stage": stage,
                    "score": evaluation.get("score"),
                }
            )
            question_refs.append(
                {
                    "question_id": qid,
                    "question_text": q.get("question_text") or "",
                    "topic": q.get("topic") or "",
                    "stage": stage,
                    "expected_points": self._parse_json_list(q.get("expected_points_json")),
                    "actual_answer": answer_by_question.get(qid, ""),
                    "score": evaluation.get("score"),
                    "matched_points": self._parse_json_list(evaluation.get("matched_points_json")),
                    "missing_points": self._parse_json_list(evaluation.get("missing_points_json")),
                }
            )

        # ① 确定性评分
        scorer = ReportScorer(
            scored_rows,
            self._parse_json(session.get("stage_plan_json")),
            self._parse_json(session.get("stage_coverage_json")),
        )
        metrics = {
            "overallScore": scorer.overall_score(),
            "readinessLevel": scorer.readiness_level(),
            "roleFitScore": scorer.role_fit_score(),
            "coverage": ReportCoverage(**scorer.coverage()),
        }

        # ② LLM 定性内容的输入上下文（纯数据组装，不调用模型）
        narrative_context = {
            "roleDirection": session.get("role_direction"),
            "profileSummary": profile,
            "analysisHints": analysis,
            "stageCoverage": metrics["coverage"].model_dump(),
            "overallScore": metrics["overallScore"],
            "questionReviews": [
                {
                    "stage": r["stage"],
                    "topic": r.get("topic"),
                    "score": r.get("score"),
                    "matched": r.get("matched_points"),
                    "missing": r.get("missing_points"),
                }
                for r in question_refs
            ],
        }

        # ③ LLM 参考答案（逐题并发）
        #
        # 刻意放在定性评语之前：参考答案是历史上失败率最高的一步（一次为全部已答题
        # 生成 → 输出被截断 → JSON 解析失败），而它排在评语之后时，评语这次成功的
        # 调用会随任务的整体重试被一起丢弃。生产数据里 8 次评语调用有 7 次是这样
        # 白跑的（约 4.5 万输入 token + 125 秒）。让高风险步骤先跑，失败即止损。
        reference_items = await ReferenceAnswerGenerator(self.settings).generate(
            question_refs, stage_by_question
        )

        # ④ LLM 定性评语（在上一步成功后才消耗 token）
        narrative = await ReportNarrativeGenerator(self.settings).generate(narrative_context)

        return ReportGenerateResult(
            reportId=str(report_id),
            overallScore=metrics["overallScore"],
            readinessLevel=metrics["readinessLevel"],
            roleFitScore=metrics["roleFitScore"],
            summary=narrative["summary"],
            strengths=narrative["strengths"],
            weaknesses=narrative["weaknesses"],
            riskPoints=narrative["riskPoints"],
            suggestions=narrative["suggestions"],
            coverage=metrics["coverage"],
            referenceAnswers=[
                ReportReferenceAnswer(**item) for item in reference_items
            ],
        )

    # ==================== MySQL 数据加载 ====================

    def _load_session(self, session_id: str) -> Mapping[str, Any]:
        with self.engine.connect() as connection:
            row = connection.execute(
                text(
                    """
                    SELECT id, user_id, resume_profile_id, profile_analysis_id,
                           role_direction, stage_plan_json, stage_coverage_json
                    FROM interview_session
                    WHERE id = :session_id AND deleted = 0
                    LIMIT 1
                    """
                ),
                {"session_id": int(session_id)},
            ).mappings().first()
        if row is None:
            raise AppError("面试会话不存在或已删除", code="SESSION_NOT_FOUND")
        return row

    def _load_report_id(self, session_id: str) -> int:
        """按会话反查报告行 ID（报告行由 Spring 在会话结束事务内预创建）。"""
        with self.engine.connect() as connection:
            row = connection.execute(
                text(
                    """
                    SELECT id
                    FROM interview_report
                    WHERE session_id = :session_id AND deleted = 0
                    LIMIT 1
                    """
                ),
                {"session_id": int(session_id)},
            ).mappings().first()
        if row is None:
            raise AppError("面试报告尚未创建", code="REPORT_NOT_FOUND")
        return int(row["id"])

    def _load_answered_questions(self, session_id: str) -> list[Mapping[str, Any]]:
        with self.engine.connect() as connection:
            return connection.execute(
                text(
                    """
                    SELECT id, question_order, stage, topic, question_text,
                           source_type, expected_points_json
                    FROM interview_question
                    WHERE session_id = :session_id
                      AND status = 'ANSWERED'
                      AND deleted = 0
                    ORDER BY question_order
                    """
                ),
                {"session_id": int(session_id)},
            ).mappings().all()

    def _load_answers(self, session_id: str) -> list[Mapping[str, Any]]:
        with self.engine.connect() as connection:
            return connection.execute(
                text(
                    """
                    SELECT question_id, answer_text
                    FROM interview_answer
                    WHERE session_id = :session_id AND deleted = 0
                    """
                ),
                {"session_id": int(session_id)},
            ).mappings().all()

    def _load_evaluations(self, session_id: str) -> list[Mapping[str, Any]]:
        with self.engine.connect() as connection:
            return connection.execute(
                text(
                    """
                    SELECT question_id, score, level, matched_points_json,
                           missing_points_json, risk_points_json
                    FROM answer_evaluation
                    WHERE session_id = :session_id AND deleted = 0
                    """
                ),
                {"session_id": int(session_id)},
            ).mappings().all()

    def _load_profile(self, profile_id: Any) -> dict[str, Any]:
        with self.engine.connect() as connection:
            row = connection.execute(
                text(
                    """
                    SELECT candidate_name, skills_json, project_experience_json
                    FROM resume_profile
                    WHERE id = :profile_id AND deleted = 0
                    LIMIT 1
                    """
                ),
                {"profile_id": int(profile_id)},
            ).mappings().first()
        if row is None:
            return {}
        return {
            "candidateName": row.get("candidate_name"),
            "skills": self._parse_json_list(row.get("skills_json")),
        }

    def _load_analysis(self, analysis_id: Any) -> dict[str, Any]:
        if analysis_id is None:
            return {}
        with self.engine.connect() as connection:
            row = connection.execute(
                text(
                    """
                    SELECT skill_tags_json, capability_hints_json, stage_targets_json
                    FROM profile_analysis
                    WHERE id = :analysis_id AND deleted = 0
                    LIMIT 1
                    """
                ),
                {"analysis_id": int(analysis_id)},
            ).mappings().first()
        if row is None:
            return {}
        return {
            "skillTags": self._parse_json_list(row.get("skill_tags_json")),
            "capabilityHints": self._parse_json(row.get("capability_hints_json")),
            "stageTargets": self._parse_json(row.get("stage_targets_json")),
        }

    @staticmethod
    def _parse_json(value: Any) -> dict[str, Any]:
        if value in (None, ""):
            return {}
        if isinstance(value, dict):
            return value
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except (TypeError, ValueError, json.JSONDecodeError):
            log.warning("报告生成读取的 JSON 字段无法解析，按空处理")
            return {}

    @staticmethod
    def _parse_json_list(value: Any) -> list[Any]:
        if value in (None, ""):
            return []
        if isinstance(value, list):
            return value
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, list) else []
        except (TypeError, ValueError, json.JSONDecodeError):
            log.warning("报告生成读取的 JSON 数组字段无法解析，按空数组处理")
            return []
