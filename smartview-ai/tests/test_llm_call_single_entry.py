"""守卫：LLM 调用入口必须唯一。

plan_1.1 §5.2 要求把三份重复的 JSON 调用实现收敛到 deepseek_client 一处，
否则埋点会漏掉画像分析和简历解析两条链路。这里用源码扫描把该约束固化下来，
避免未来重构时又长出第二份 httpx 调用——这类重复不会被任何功能测试发现。
"""
from pathlib import Path

APP_DIR = Path(__file__).resolve().parents[1] / "app"

# 除公共 LLM 客户端外，任何模块都不允许直接请求 Chat Completions 端点。
CHAT_ENDPOINT = "/chat/completions"
# 允许出现 httpx 的位置：公共 LLM 客户端，以及做文本向量的 Embedding 客户端
# （Embedding 不是 Chat 调用，不走 JSON 模式，也不在本期埋点范围内）。
ALLOWED_HTTPX = {"services/deepseek_client.py", "clients/qwen_embedding.py"}


def _python_files() -> list[Path]:
    return [path for path in APP_DIR.rglob("*.py") if path.is_file()]


def _relative(path: Path) -> str:
    return str(path.relative_to(APP_DIR)).replace("\\", "/")


def test_only_deepseek_client_calls_chat_completions() -> None:
    offenders = sorted(
        _relative(path)
        for path in _python_files()
        if CHAT_ENDPOINT in path.read_text(encoding="utf-8")
    )
    assert set(offenders) <= {"services/deepseek_client.py"}, (
        f"以下模块绕过公共 LLM 入口直接请求 Chat Completions：{offenders}"
    )


def test_httpx_is_confined_to_the_public_clients() -> None:
    offenders = sorted(
        _relative(path)
        for path in _python_files()
        if "import httpx" in path.read_text(encoding="utf-8")
    )
    assert set(offenders) <= ALLOWED_HTTPX, f"出现了预期之外的 httpx 依赖：{offenders}"
