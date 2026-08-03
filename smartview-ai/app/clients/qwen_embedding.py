"""Qwen 文本向量模型 Embedding 函数。

替换 Chroma 默认的英文 all-MiniLM-L6-v2，改用 Qwen 文本向量模型（OpenAI 兼容
接口），中文检索效果更好。文档入库与查询共用同一函数，保证两端向量由同一模型
产生、维度一致（qwen3.7-text-embedding 返回 1024 维）。

实现说明：
- 通过 httpx 直接调用 `{base_url}/embeddings`，不额外引入 openai 依赖；
- 用普通类实现 Chroma 的 EmbeddingFunction 协议（__call__/name/get_config/
  build_from_config），避免在模块顶部 import chromadb，保持依赖延迟加载，
  让不需要向量库的单元测试可以完全不加载 chromadb；
- get_config 只保存环境变量名而非密钥值，避免 API key 明文落入 Chroma 的
  SQLite collection 配置。
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.core.config import Settings, get_settings

log = logging.getLogger(__name__)

# 单次请求嵌入的文本条数上限：避免超大 batch 拉长单请求、放大超时风险
_BATCH_SIZE = 8
# 请求超时（秒）：向量化属于检索/入库路径，超时按失败处理并走既有降级逻辑
_TIMEOUT_SECONDS = 60.0


class QwenEmbeddingFunction:
    """通过 Qwen 文本向量模型把文本转为向量，满足 Chroma EmbeddingFunction 协议。

    协议要求的公开方法：
    - __call__(input: list[str]) -> list[list[float]]：文档与查询统一入口；
    - name()：Chroma 持久化/比对 collection 时使用的稳定函数名；
    - get_config()：可序列化配置（不包含密钥）；
    - build_from_config(config)：Chroma 按持久化配置重建时调用。

    密钥（api key）只在运行时从 Settings/.env 读取，不进入持久化配置，
    避免敏感信息落盘到 Chroma 的 SQLite 文件。
    """

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        config: dict[str, Any] | None = None,
    ) -> None:
        settings = settings or get_settings()
        if config:
            # build_from_config 场景：模型与 base URL 以持久化配置为准，保证与创建时一致
            self._model = str(config.get("model") or settings.qwen_embedding_model)
            self._base_url = str(
                config.get("base_url") or settings.qwen_embedding_base_url
            ).rstrip("/")
        else:
            self._model = settings.qwen_embedding_model
            self._base_url = settings.qwen_embedding_base_url.rstrip("/")
        self._api_key = settings.qwen_embedding_api_key.get_secret_value()
        self._timeout = _TIMEOUT_SECONDS

    def is_legacy(self) -> bool:
        """走 Chroma 新版 EF 配置协议（get_config/build_from_config）。

        返回 False 避免被当作被弃用的 legacy 配置路径处理，
        否则持久化 collection 配置时会触发 DeprecationWarning。
        """
        return False

    def default_space(self) -> str:
        """默认距离空间：与 collection 创建时显式设置的 hnsw:space 保持一致。"""
        return "cosine"

    def supported_spaces(self) -> list[str]:
        """支持的距离空间列表，供 Chroma 校验 collection 元数据。"""
        return ["cosine"]

    def __call__(self, input: list[str]) -> list[list[float]]:
        """把一批文本转为向量；分批请求并保持顺序，返回与输入等长的向量列表。"""
        if not input:
            return []
        if not self._api_key:
            raise ValueError("未配置 QWEN_EMBEDDING_API_KEY，无法使用 Qwen 文本向量模型")
        if not self._base_url:
            raise ValueError("未配置 QWEN_EMBEDDING_BASE_URL，无法使用 Qwen 文本向量模型")

        embeddings: list[list[float]] = []
        with httpx.Client(timeout=self._timeout) as client:
            for start in range(0, len(input), _BATCH_SIZE):
                batch = input[start : start + _BATCH_SIZE]
                embeddings.extend(self._embed_batch(client, batch))
        return embeddings

    def embed_query(self, input: list[str]) -> list[list[float]]:
        """查询文本入口：Qwen 向量模型对文档与查询用同一编码，直接复用 __call__。"""
        return self.__call__(input)

    def _embed_batch(self, client: httpx.Client, texts: list[str]) -> list[list[float]]:
        """调用单批文本的 embeddings 接口，返回按请求顺序排列的向量。"""
        response = client.post(
            f"{self._base_url}/embeddings",
            headers={
                "Authorization": f"Bearer {self._api_key}",
                "Content-Type": "application/json",
            },
            json={"model": self._model, "input": texts},
        )
        if response.status_code != 200:
            # 只带状态码与响应片段，不打印密钥，便于定位接口/鉴权问题
            raise RuntimeError(
                f"Qwen embedding 接口返回 {response.status_code}: {response.text[:200]}"
            )
        data = response.json()
        vectors = [item.get("embedding") for item in data.get("data", [])]
        if len(vectors) != len(texts):
            raise RuntimeError(
                f"Qwen embedding 返回条数 {len(vectors)} 与请求 {len(texts)} 不一致"
            )
        return [list(map(float, vector)) for vector in vectors]

    @staticmethod
    def name() -> str:
        """Chroma 持久化/比对 collection 时使用的稳定函数名，不能随意改动。"""
        return "qwen_text_embedding"

    def get_config(self) -> dict[str, Any]:
        """返回可序列化配置；只存环境变量名而非密钥，避免密钥明文落盘。"""
        return {
            "model": self._model,
            "base_url": self._base_url,
            "api_key_env_var": "QWEN_EMBEDDING_API_KEY",
        }

    @staticmethod
    def build_from_config(config: dict[str, Any]) -> "QwenEmbeddingFunction":
        """Chroma 按持久化配置重建函数时调用；api key 运行时从 Settings 读取。"""
        return QwenEmbeddingFunction(config=config)
