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
# 覆盖面按"MySQL 真实语法"补齐，否则守卫会被绕过：
# - INSERT 允许 IGNORE / LOW_PRIORITY / HIGH_PRIORITY 修饰；
# - DELETE 允许 MySQL 多表删除语法 `DELETE a FROM tbl a JOIN ...`；
# - TRUNCATE 允许省略关键字 TABLE；
# - UPDATE 允许 LOW_PRIORITY / IGNORE 修饰（修饰词不能被当成表名）。
_WRITE_PATTERN = re.compile(
    r"\b(?:INSERT(?:\s+(?:IGNORE|LOW_PRIORITY|HIGH_PRIORITY))?\s+INTO"
    r"|REPLACE(?:\s+(?:LOW_PRIORITY|DELAYED))?\s+INTO"
    r"|DELETE(?:\s+\w+)?\s+FROM"
    r"|TRUNCATE(?:\s+TABLE)?"
    r"|UPDATE(?:\s+(?:LOW_PRIORITY|IGNORE))?)\s+"
    r"`?([A-Za-z_][A-Za-z0-9_]*)`?(?:\.`?([A-Za-z_][A-Za-z0-9_]*)`?)?",
    re.IGNORECASE,
)
# 行首注释里的 SQL 只是文字说明（例如"# 禁止 UPDATE interview_answer 直接改分"），
# 扫描前先剥离，避免把注释误报成真实写语句。
# 刻意只剥离行首注释：行内尾随注释若被整体剥离，可能连带删掉同一行的真实语句；
# 误报会让 CI 变红并被立刻修正，漏检则是静默的，因此宁可保留少量误报。
_LINE_COMMENT = re.compile(r"^[ \t]*#[^\n]*", re.MULTILINE)
# INSERT 的修饰词不能当表名
_INSERT_KEYWORDS = {"ignore", "low_priority", "high_priority", "delayed"}


def _python_files() -> list[Path]:
    return [path for path in APP_DIR.rglob("*.py") if path.is_file()]


def _relative(path: Path) -> str:
    return str(path.relative_to(APP_DIR)).replace("\\", "/")


def _written_tables() -> dict[str, set[str]]:
    """返回 {相对文件路径: 该文件写语句涉及的表名集合}。

    只做文本级扫描：SQL 都是模块常量或字面量字符串，不涉及动态拼接，
    因此源码扫描足以覆盖，且比运行期探针更能拦住"还没被调用到"的新写语句。

    已知覆盖边界（不追求完备，追求"新增写语句时大概率被拦住"）：
    - ORM 写入（session.add）与 f-string 动态拼表名不在扫描范围内；
    - 注释与文档字符串会被剥离，避免误报。
    """
    written: dict[str, set[str]] = {}
    for path in _python_files():
        content = path.read_text(encoding="utf-8")
        scannable = _LINE_COMMENT.sub("", content)
        tables: set[str] = set()
        for match in _WRITE_PATTERN.finditer(scannable):
            # 带 schema 前缀时取 schema 之后的部分，例如 smartview.user → user
            name = match.group(2) or match.group(1)
            if name.lower() in _INSERT_KEYWORDS:
                continue
            tables.add(name)
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


def _tables_in(sql: str) -> set[str]:
    """对单段 SQL 应用同一套识别逻辑，用于验证正则本身的覆盖面。"""
    return {
        name
        for match in _WRITE_PATTERN.finditer(_LINE_COMMENT.sub("", sql))
        if (name := match.group(2) or match.group(1)).lower() not in _INSERT_KEYWORDS
    }


def test_write_detection_covers_mysql_syntax_variants() -> None:
    """守卫的正则本身也要有断言：漏检是静默失效，误报才会显形。

    这里的样本取自 MySQL 允许的等价写法——漏掉任意一种，新增写语句都能绕过守卫。
    """
    samples = [
        "INSERT INTO interview_answer (a) VALUES (1)",
        "INSERT IGNORE INTO interview_answer (a) VALUES (1)",
        "INSERT LOW_PRIORITY INTO interview_answer (a) VALUES (1)",
        "REPLACE INTO interview_answer (a) VALUES (1)",
        "INSERT INTO interview_answer (a) SELECT a FROM report",
        "DELETE FROM interview_answer WHERE id = 1",
        "DELETE a FROM interview_answer a JOIN report r ON r.id = a.id",
        "UPDATE interview_answer SET score = 1 WHERE id = 1",
        "UPDATE LOW_PRIORITY interview_answer SET score = 1",
        "TRUNCATE TABLE interview_answer",
        "TRUNCATE interview_answer",
        "INSERT INTO smartview.interview_answer (a) VALUES (1)",
        "INSERT INTO `interview_answer` (a) VALUES (1)",
    ]
    for sql in samples:
        assert "interview_answer" in _tables_in(sql), f"漏检写语句：{sql}"


def test_comment_only_sql_is_not_reported() -> None:
    """注释里的 SQL 是文字说明，不能算违规，否则守卫会因误报把 CI 弄红。"""
    assert _tables_in("# 禁止 UPDATE interview_answer 直接改分\n") == set()
