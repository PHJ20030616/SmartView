"""守卫：FastAPI 不写业务表。

AGENTS.md 的架构调用规则要求 FastAPI 对业务表只读；plan_1.1 §5.4 为 LLM 调用日志
登记了唯一例外（技术可观测表 llm_call_log）。这里用源码扫描把该边界固化成可执行断言：

- 边界写在文档里只是约定，写在测试里才是约束；
- 这类违规不会让任何功能测试变红——多写一张表在开发期完全"看起来正常"，
  代价要到线上出现数据归属错乱时才显现。
"""
import re
from pathlib import Path

APP_DIR = Path(__file__).resolve().parents[1] / "app"

# 唯一允许 FastAPI 写入的表
WRITE_ALLOWED = {"llm_call_log"}
# 业务表：写入即违规（读也在 AGENTS.md 的禁止范围内，但本测试只针对"写"这一不可逆动作）
BUSINESS_TABLES = {
    "user",
    "resume_file",
    "resume_profile",
    "profile_analysis",
    "resume_vector_task",
    "ai_task",
    "interview_session",
    "interview_question",
    "interview_answer",
    "answer_evaluation",
    "interview_report",
    "reference_answer",
}

# 识别写语句并捕获其目标表名。表名允许反引号（MySQL 风格）与 schema 前缀。
_WRITE_PATTERN = re.compile(
    r"\b(?:INSERT\s+INTO|REPLACE\s+INTO|DELETE\s+FROM|TRUNCATE\s+TABLE|UPDATE)\s+"
    r"`?([A-Za-z_][A-Za-z0-9_]*)`?(?:\.`?([A-Za-z_][A-Za-z0-9_]*)`?)?",
    re.IGNORECASE,
)


def _python_files() -> list[Path]:
    return [path for path in APP_DIR.rglob("*.py") if path.is_file()]


def _relative(path: Path) -> str:
    return str(path.relative_to(APP_DIR)).replace("\\", "/")


def _written_tables() -> dict[str, set[str]]:
    """返回 {相对文件路径: 该文件写语句涉及的表名集合}。

    只做文本级扫描：SQL 都是模块常量或字面量字符串，不涉及动态拼接，
    因此源码扫描足以覆盖，且比运行期探针更能拦住"还没被调用到"的新写语句。
    """
    written: dict[str, set[str]] = {}
    for path in _python_files():
        content = path.read_text(encoding="utf-8")
        tables: set[str] = set()
        for match in _WRITE_PATTERN.finditer(content):
            # 带 schema 前缀时取 schema 之后的部分，例如 smartview.user → user
            tables.add(match.group(2) or match.group(1))
        if tables:
            written[_relative(path)] = tables
    return written


def test_business_tables_are_never_written() -> None:
    offenders = {
        file: sorted(names & BUSINESS_TABLES)
        for file, names in _written_tables().items()
        if names & BUSINESS_TABLES
    }
    assert not offenders, (
        f"FastAPI 侧出现了对业务表的写语句，违反 AGENTS.md 架构调用规则：{offenders}"
    )


def test_llm_call_log_is_the_only_written_table() -> None:
    unexpected = {
        file: sorted(names - WRITE_ALLOWED)
        for file, names in _written_tables().items()
        if names - WRITE_ALLOWED
    }
    assert not unexpected, f"写入了白名单之外的表（当前仅允许 llm_call_log）：{unexpected}"
