"""pytest 全局配置：测试环境关闭文件日志，避免污染仓库 logs 目录。"""
import os

import pytest

os.environ["LOG_FILE_ENABLED"] = "false"


@pytest.fixture(autouse=True)
def isolate_process_environ():
    """按用例隔离真实进程环境变量，防止用例之间的隐式串味。

    背景：生产代码 app/core/config.py 的 _load_shared_infra_env() 按设计会**直接写
    os.environ**（为了让共享凭据对 Pydantic Settings 生效）。但 load_dotenv(override=False)
    只跳过"已存在"的键：在没有 smartview-infra/.env 的环境（例如 CI，该文件被 .gitignore 忽略），
    tests/test_config.py 里临时构造的 MYSQL_HOST=dbhost 会被真实写入且无人回滚，
    残留给同一会话中后续用例，导致连不上数据库、报出与本用例无关的失败。

    这类污染具有环境依赖性——本地存在 smartview-infra/.env 时，
    MYSQL_HOST 早已是 localhost，override=False 使其保持原值，因此本地完全看不到问题，
    只有 CI 才会暴露。这里对每个用例整体快照并还原，保证用例结果与执行顺序、
    与运行机器上是否存在 .env 都无关。
    """
    snapshot = dict(os.environ)
    try:
        yield
    finally:
        os.environ.clear()
        os.environ.update(snapshot)
        # 还原环境变量之后，还要丢掉 Settings 的 lru_cache 缓存。
        # 否则用例 A 在"改了环境变量"的状态下调用 get_settings()，缓存的实例会活到用例 B，
        # 使 B 读到 A 的配置——这同样是顺序依赖，只是更隐蔽（当前靠各处手工调用
        # cache_clear() 兜底，属分散约定而非机制保障）。
        #
        # 注意这里的导入必须放在函数内：app/core/config.py 在模块级就会执行
        # _load_shared_infra_env()，把它提到文件顶部会让"真实 .env 的值"在收集阶段
        # 就进入 os.environ，之后每个用例的快照都带着它，反而让用例重新依赖本机环境。
        from app.core.config import get_settings

        get_settings.cache_clear()
