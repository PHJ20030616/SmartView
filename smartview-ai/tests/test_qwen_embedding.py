"""QwenEmbeddingFunction 测试：分批请求、鉴权、顺序保持、错误处理与 Chroma 集成。"""

from __future__ import annotations

import numpy as np

from app.clients.chroma_client import get_or_create_collection
from app.clients.qwen_embedding import QwenEmbeddingFunction
from app.core.config import Settings


class FakeResponse:
    """模拟 httpx.Response：按输入文本生成确定性向量，便于断言顺序与数量。"""

    def __init__(self, embeddings: list[list[float]], status_code: int = 200) -> None:
        self._embeddings = embeddings
        self.status_code = status_code
        self.text = "fake response"

    def json(self) -> dict:
        return {
            "data": [
                {"embedding": vector} for vector in self._embeddings
            ],
            "model": "qwen3.7-text-embedding",
        }


class FakeClient:
    """模拟 httpx.Client：记录请求，返回与文本对应的确定性向量。"""

    def __init__(self, status_code: int = 200) -> None:
        self.requests: list[dict] = []
        self._status_code = status_code

    def __enter__(self) -> "FakeClient":
        return self

    def __exit__(self, *args: object) -> bool:
        return False

    def post(self, url: str, *, headers: dict | None = None, json: dict | None = None):
        self.requests.append({"url": url, "headers": headers, "json": json})
        texts = json.get("input", []) if json else []
        if self._status_code != 200:
            return FakeResponse([], status_code=self._status_code)
        embeddings = [_fake_embedding(text) for text in texts]
        return FakeResponse(embeddings)


def _fake_embedding(text: str) -> list[float]:
    """用文本内容生成确定性高维向量：不同文本向量不同、同文本稳定。

    每个字符按 Unicode 码点散列到固定 64 维桶中累加，并叠加位置信息。
    相比原先的 2 维 [码点和, 长度]，不同文本的向量方向差异足够大，
    避免 HNSW 近似索引把方向几乎相同的文本混为一谈导致查询偶发误判。
    """
    vector = [0.0] * 64
    for index, char in enumerate(text):
        vector[ord(char) % 64] += 1.0
        vector[index % 64] += 0.001
    return vector


def _make_settings(
    api_key: str = "test-key",
    base_url: str = "https://emb.example.com",
    model: str = "qwen3.7-text-embedding",
    **extra: object,
) -> Settings:
    return Settings(
        qwen_embedding_api_key=api_key,
        qwen_embedding_base_url=base_url,
        qwen_embedding_model=model,
        _env_file=None,
        **extra,
    )


def test_embedding_batches_preserves_order_and_auth(monkeypatch) -> None:
    """超过批大小按批请求，鉴权头正确，返回顺序与输入一一对应。"""
    client = FakeClient()
    monkeypatch.setattr("app.clients.qwen_embedding.httpx.Client", lambda **kw: client)

    texts = [f"测试文本{i}" for i in range(10)]  # 10 条 > 批大小 8，应分两批
    vectors = QwenEmbeddingFunction(_make_settings())(texts)

    assert len(vectors) == 10
    assert len(client.requests) == 2
    assert [len(request["json"]["input"]) for request in client.requests] == [8, 2]
    # 鉴权头使用 Bearer + 密钥
    assert client.requests[0]["headers"]["Authorization"] == "Bearer test-key"
    # 每个输出向量必须与其输入文本一一对应（验证顺序保持）；float64→float32
    # 转换存在极小舍入误差，用 allclose 近似比较而非精确相等
    assert all(
        np.allclose(vectors[i], _fake_embedding(texts[i]), atol=1e-6) for i in range(10)
    )
    # Chroma HTTP 模式的 query 会对向量调用 numpy 的 .tolist()，必须返回 ndarray
    assert all(isinstance(vector, np.ndarray) for vector in vectors)
    assert all(vector.dtype == np.float32 for vector in vectors)


def test_embedding_empty_input_returns_empty() -> None:
    """空输入直接返回空列表，不发请求。"""
    assert QwenEmbeddingFunction(_make_settings())([]) == []


def test_embedding_missing_api_key_raises(monkeypatch) -> None:
    """未配置 API 密钥时明确报错，而不是静默失败。"""
    monkeypatch.setattr("app.clients.qwen_embedding.httpx.Client", lambda **kw: FakeClient())
    ef = QwenEmbeddingFunction(_make_settings(api_key=""))

    assert "QWEN_EMBEDDING_API_KEY" in str(_raises(ValueError, lambda: ef(["文本"])))


def test_embedding_missing_base_url_raises() -> None:
    """未配置 base URL 时明确报错。"""
    ef = QwenEmbeddingFunction(_make_settings(base_url=""))

    assert "QWEN_EMBEDDING_BASE_URL" in str(_raises(ValueError, lambda: ef(["文本"])))


def test_embedding_api_error_raises(monkeypatch) -> None:
    """接口非 200 时抛出带状态码的错误，便于定位鉴权/网络问题。"""
    client = FakeClient(status_code=401)
    monkeypatch.setattr("app.clients.qwen_embedding.httpx.Client", lambda **kw: client)
    ef = QwenEmbeddingFunction(_make_settings())

    error = _raises(RuntimeError, lambda: ef(["文本"]))
    assert "401" in str(error)


def test_embedding_return_count_mismatch_raises(monkeypatch) -> None:
    """接口返回条数与请求不一致时拒绝静默错位。"""
    class ShortClient(FakeClient):
        def post(self, url, *, headers=None, json=None):
            self.requests.append({"url": url, "headers": headers, "json": json})
            return FakeResponse([_fake_embedding("少一条")])  # 只返回 1 条

    monkeypatch.setattr(
        "app.clients.qwen_embedding.httpx.Client", lambda **kw: ShortClient()
    )
    ef = QwenEmbeddingFunction(_make_settings())

    assert "条数" in str(_raises(RuntimeError, lambda: ef(["a", "b"])))


def test_get_config_does_not_contain_api_key() -> None:
    """持久化配置不得包含密钥明文，只存环境变量名。"""
    config = QwenEmbeddingFunction(_make_settings()).get_config()

    assert config["model"] == "qwen3.7-text-embedding"
    assert config["base_url"] == "https://emb.example.com"
    assert config["api_key_env_var"] == "QWEN_EMBEDDING_API_KEY"
    assert "test-key" not in str(config)


def test_name_is_stable_and_build_from_config() -> None:
    """name 稳定用于 Chroma 比对；build_from_config 按持久化配置重建模型与地址。"""
    assert QwenEmbeddingFunction.name() == "qwen_text_embedding"

    rebuilt = QwenEmbeddingFunction.build_from_config(
        {"model": "m1", "base_url": "https://x.example.com/"}
    )
    assert rebuilt._model == "m1"
    assert rebuilt._base_url == "https://x.example.com"


def test_collection_create_reopen_query_with_qwen_ef(tmp_path, monkeypatch) -> None:
    """真实 Chroma：用 Qwen EF 建库写入后重开查询，验证协议与 collection 生命周期兼容。

    使用 persistent 模式指向临时目录，避免测试依赖 Docker Chroma server；
    生产默认走 http 模式连接 server（见 app/clients/chroma_client.py）。
    """
    client = FakeClient()
    monkeypatch.setattr("app.clients.qwen_embedding.httpx.Client", lambda **kw: client)
    settings = _make_settings(
        chroma_mode="persistent", chroma_persist_directory=str(tmp_path)
    )

    collection = get_or_create_collection(settings, "test_knowledge_base")
    collection.add(
        ids=["kb:1", "kb:2"],
        documents=["Redis 缓存穿透的解决方案", "RabbitMQ 消息确认机制"],
        metadatas=[
            {"title": "Redis", "tags": ["Redis"]},
            {"title": "MQ", "tags": ["RabbitMQ"]},
        ],
    )
    assert collection.count() == 2

    # 重开同一 collection（传入相同的 Qwen EF），Chroma 应能比对配置并正常查询。
    # 用与库中完全一致的文本查询：fake 向量对相同文本返回相同向量，
    # 余弦距离为 0，保证精确命中，验证的是协议生命周期而非语义检索效果。
    reopened = get_or_create_collection(settings, "test_knowledge_base")
    result = reopened.query(query_texts=["Redis 缓存穿透的解决方案"], n_results=1)

    assert result["documents"] == [["Redis 缓存穿透的解决方案"]]


def _raises(exc_type: type[Exception], fn) -> Exception:
    """执行 fn 并断言抛出指定类型异常，返回该异常。"""
    try:
        fn()
    except exc_type as exc:  # type: ignore[misc]
        return exc
    raise AssertionError(f"预期抛出 {exc_type.__name__}，但未抛出")
